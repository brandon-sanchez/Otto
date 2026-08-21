package otto.infra;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the deploy identity promises, read back off the template. Every
 * assertion here is a way the pipeline could be handed to someone it
 * was not meant for, so each one is worth a test rather than a review.
 */
class DeployRoleStackTest {

    private static final String ACCOUNT = "111111111111";

    private static Template template;

    @BeforeAll
    static void synth() {
        template = Template.fromStack(new DeployRoleStack(new App(), "OttoDeployRoleStack",
                StackProps.builder()
                        .env(Environment.builder()
                                .account(ACCOUNT)
                                .region("us-east-1")
                                .build())
                        .build()));
    }

    @Test
    void githubIsTheOnlyIdentityProviderAndItsTokensMustNameAws() {
        template.resourceCountIs("AWS::IAM::OIDCProvider", 1);
        template.hasResourceProperties("AWS::IAM::OIDCProvider", Map.of(
                "Url", "https://token.actions.githubusercontent.com",
                "ClientIdList", List.of("sts.amazonaws.com")));
    }

    /**
     * The repository is public, so a stranger can open a pull request
     * and run a workflow against it. What keeps that workflow away from
     * the deploy role is this one condition, matched exactly: a
     * StringLike with a wildcard would let any branch, and any fork,
     * present a token that fits.
     *
     * <p>Both spellings of the subject are accepted, and both are
     * exact. GitHub sends the one carrying numeric ids; the readable
     * one is kept in case that changes back.
     */
    @Test
    void onlyMainOnThisRepositoryCanAssumeTheRole() {
        Map<?, ?> condition = trustCondition();
        Map<?, ?> equals = (Map<?, ?>) condition.get("StringEquals");

        assertThat(equals.get("token.actions.githubusercontent.com:aud"))
                .isEqualTo("sts.amazonaws.com");
        assertThat((List<?>) equals.get("token.actions.githubusercontent.com:sub"))
                .extracting(String::valueOf)
                .containsExactlyInAnyOrder(
                        "repo:brandon-sanchez/Otto:ref:refs/heads/main",
                        "repo:brandon-sanchez@83891046/Otto@1325745376:ref:refs/heads/main");
        assertThat(String.valueOf(condition))
                .as("no loose matcher alongside the exact ones")
                .doesNotContain("StringLike");
    }

    /**
     * The first live deploy failed because the policy listed only the
     * readable subject and GitHub sent the one with ids. Every accepted
     * subject must still name this repository and this branch, so a
     * later edit cannot quietly widen the door.
     */
    @Test
    void everyAcceptedSubjectNamesThisRepositoryAndOnlyMain() {
        Map<?, ?> equals = (Map<?, ?>) trustCondition().get("StringEquals");

        assertThat((List<?>) equals.get("token.actions.githubusercontent.com:sub"))
                .allSatisfy(subject -> assertThat(String.valueOf(subject))
                        .startsWith("repo:brandon-sanchez")
                        .contains("/Otto")
                        .endsWith(":ref:refs/heads/main")
                        .doesNotContain("*"));
    }

    @Test
    void theTrustIsAWebIdentityHandshakeAndNotALongLivedKey() {
        Map<?, ?> statement = trustStatement();

        assertThat(statement.get("Action")).isEqualTo("sts:AssumeRoleWithWebIdentity");
        assertThat(String.valueOf(statement.get("Principal")))
                .as("the federated principal is the GitHub provider this stack creates")
                .contains("GithubOidc");
        template.resourceCountIs("AWS::IAM::User", 0);
        template.resourceCountIs("AWS::IAM::AccessKey", 0);
    }

    /**
     * The role is a doorway to the bootstrap roles and nothing else. A
     * deploy that needs a permission it does not have should fail
     * loudly rather than find one waiting here.
     */
    @Test
    void theRoleMayAssumeTheThreeBootstrapRolesAndNothingElse() {
        List<?> statements = inlineStatements();

        assertThat(statements).hasSize(1);
        Map<?, ?> statement = (Map<?, ?>) statements.getFirst();
        assertThat(statement.get("Action")).isEqualTo("sts:AssumeRole");

        List<String> arns = ((List<?>) statement.get("Resource")).stream()
                .map(String::valueOf)
                .toList();
        assertThat(arns).as("three roles, and no fourth").hasSize(3);
        assertThat(arns)
                .as("the partition is read off the stack rather than assumed to be \"aws\"")
                .allSatisfy(arn -> assertThat(arn).contains("Ref=AWS::Partition"));
        assertThat(String.valueOf(arns))
                .contains("cdk-hnb659fds-lookup-role-%s-us-east-1".formatted(ACCOUNT))
                .contains("cdk-hnb659fds-file-publishing-role-%s-us-east-1".formatted(ACCOUNT))
                .contains("cdk-hnb659fds-deploy-role-%s-us-east-1".formatted(ACCOUNT));
    }

    /**
     * cfn-exec is the role CloudFormation itself assumes, and it
     * carries the permissions to create anything in the account.
     * Granting it to the pipeline would hand a workflow the account.
     */
    @Test
    void theExecutionRoleIsNotSomethingThePipelineCanAssume() {
        assertThat(String.valueOf(inlineStatements())).doesNotContain("cfn-exec");
    }

    @Test
    void noPolicyReachesEveryResourceOrEveryAction() {
        String policies = String.valueOf(template.findResources("AWS::IAM::Policy"));

        assertThat(policies).doesNotContain("\"*\"");
        assertThat(template.findResources("AWS::IAM::Role").values())
                .as("nothing managed and account-wide, such as AdministratorAccess")
                .allSatisfy(role -> assertThat(String.valueOf(role))
                        .doesNotContain("ManagedPolicyArns"));
    }

    /**
     * The workflow holds this ARN as a repository secret. A generated
     * name would change on a redeploy and break the pipeline in a way
     * that looks like an AWS outage.
     */
    @Test
    void theRoleNameIsFixedSoTheWorkflowsArnKeepsWorking() {
        template.hasResourceProperties("AWS::IAM::Role",
                Match.objectLike(Map.of("RoleName", "otto-github-deploy")));
        assertThat(template.findOutputs("DeployRoleArn")).isNotEmpty();
    }

    private Map<?, ?> trustStatement() {
        Map<String, Map<String, Object>> roles = template.findResources("AWS::IAM::Role");
        assertThat(roles).hasSize(1);
        Map<?, ?> properties = (Map<?, ?>) roles.values().iterator().next().get("Properties");
        Map<?, ?> document = (Map<?, ?>) properties.get("AssumeRolePolicyDocument");
        List<?> statements = (List<?>) document.get("Statement");
        assertThat(statements).as("one way in, not several").hasSize(1);
        return (Map<?, ?>) statements.getFirst();
    }

    private Map<?, ?> trustCondition() {
        return (Map<?, ?>) trustStatement().get("Condition");
    }

    private List<?> inlineStatements() {
        Map<String, Map<String, Object>> policies = template.findResources("AWS::IAM::Policy");
        assertThat(policies).hasSize(1);
        Map<?, ?> properties = (Map<?, ?>) policies.values().iterator().next().get("Properties");
        Map<?, ?> document = (Map<?, ?>) properties.get("PolicyDocument");
        return (List<?>) document.get("Statement");
    }
}
