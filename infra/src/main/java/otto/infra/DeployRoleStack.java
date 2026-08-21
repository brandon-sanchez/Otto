package otto.infra;

import java.util.List;
import java.util.Map;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.iam.CfnOIDCProvider;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.FederatedPrincipal;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.constructs.Construct;

/**
 * The identity GitHub Actions deploys as.
 *
 * <p>This is its own stack, and not part of {@link OttoStack}, because
 * of what the split buys. The workflow deploys {@code OttoStack} by
 * name, so it never holds the template that defines its own
 * permissions: widening the deploy role has to be a human running
 * {@code cdk deploy} locally, not a commit landing on main. The two
 * also live on different clocks - the assistant changes weekly and
 * this changes about never.
 *
 * <p>There are no access keys anywhere. GitHub mints a short-lived
 * OIDC token for a workflow run, AWS trades it for session
 * credentials, and the trust policy pins who may make that trade. The
 * repository is public, so anyone can open a pull request against it;
 * the subject condition is what stops a fork or a branch from
 * borrowing the deploy role, and it is the only thing that does.
 */
public class DeployRoleStack extends Stack {

    private static final String OIDC_URL = "https://token.actions.githubusercontent.com";

    /**
     * The audience the workflow requests. AWS rejects a token minted
     * for anyone else, which is what stops a token issued for some
     * other cloud from being replayed here.
     */
    private static final String AUDIENCE = "sts.amazonaws.com";

    /**
     * Exactly one repository and exactly one branch may assume the
     * role, matched with StringEquals rather than StringLike. A
     * wildcard here is the standard way this goes wrong: {@code
     * repo:owner/Otto:*} would also match every pull request branch,
     * including one opened from a fork by a stranger.
     */
    private static final String SUBJECT = "repo:brandon-sanchez/Otto:ref:refs/heads/main";

    /**
     * The qualifier CDK bootstrapped this account with - the {@code
     * hnb659fds} in every bootstrap role name. It is the default, and
     * it is spelled here because the role has to name the exact roles
     * it may assume. Re-bootstrapping under a custom qualifier means
     * changing this.
     */
    private static final String QUALIFIER = "hnb659fds";

    /**
     * The three roles the CDK CLI assumes on a deploy: one to read
     * context, one to upload the asset, one to drive CloudFormation.
     * The fourth bootstrap role, {@code cfn-exec}, is assumed by
     * CloudFormation itself rather than by the CLI, so it is not
     * granted here.
     */
    private static final List<String> BOOTSTRAP_ROLES =
            List.of("lookup", "file-publishing", "deploy");

    /**
     * Fixed, so the workflow's role ARN survives a redeploy of this
     * stack. A generated name would change and silently break the
     * pipeline.
     */
    private static final String ROLE_NAME = "otto-github-deploy";

    public DeployRoleStack(Construct scope, String id, StackProps props) {
        super(scope, id, props);

        CfnOIDCProvider github = CfnOIDCProvider.Builder.create(this, "GithubOidc")
                .url(OIDC_URL)
                // No thumbprint list. IAM retrieves the provider's
                // intermediate CA thumbprint itself, so pinning one
                // here would only be a value that expires one day and
                // fails a deploy for a reason nobody remembers.
                .clientIdList(List.of(AUDIENCE))
                .build();

        Role deployer = Role.Builder.create(this, "GithubDeployRole")
                .roleName(ROLE_NAME)
                .description("Assumed by GitHub Actions on a push to main, to deploy OttoStack")
                .assumedBy(new FederatedPrincipal(
                        github.getAttrArn(),
                        Map.of("StringEquals", Map.of(
                                "token.actions.githubusercontent.com:aud", AUDIENCE,
                                "token.actions.githubusercontent.com:sub", SUBJECT)),
                        "sts:AssumeRoleWithWebIdentity"))
                .build();

        // The role carries no AWS permissions of its own. Everything a
        // deploy does, it does through the bootstrap roles, which
        // already scope what a CDK deploy is allowed to touch - so
        // there is no second, drifting copy of that list to maintain.
        deployer.addToPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("sts:AssumeRole"))
                .resources(BOOTSTRAP_ROLES.stream().map(this::bootstrapRoleArn).toList())
                .build());

        CfnOutput.Builder.create(this, "DeployRoleArn")
                .value(deployer.getRoleArn())
                .description("Set this as the AWS_DEPLOY_ROLE_ARN repository secret")
                .build();
    }

    private String bootstrapRoleArn(String role) {
        return "arn:%s:iam::%s:role/cdk-%s-%s-role-%s-%s"
                .formatted(getPartition(), getAccount(), QUALIFIER, role,
                        getAccount(), getRegion());
    }
}
