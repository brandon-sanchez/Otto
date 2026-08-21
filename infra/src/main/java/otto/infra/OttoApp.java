package otto.infra;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

/**
 * The CDK entry point. One stage, one region: the assistant serves one
 * league for one user, and a dev stage would be a second thing to keep
 * alive for no one's benefit.
 *
 * <p>Two stacks share that stage, and the split is about who deploys
 * them rather than about size. {@link OttoStack} is the assistant, and
 * GitHub Actions deploys it on every push to main. {@link
 * DeployRoleStack} is the identity Actions deploys as, and a human
 * deploys it by hand, so that a commit cannot change what the pipeline
 * is allowed to do. Because they are named stacks in one app, a bare
 * "cdk deploy" now refuses and asks which - that refusal is the point.
 */
public final class OttoApp {

    /**
     * us-east-1, as the spec names. The Sleeper and nflverse hosts are
     * public and the user is not in the region, so the region is chosen
     * for the widest service coverage rather than for latency.
     */
    private static final String REGION = "us-east-1";

    private OttoApp() {
    }

    public static void main(String[] args) {
        App app = new App();
        new OttoStack(app, "OttoStack",
                props("Otto, the fantasy football assistant"));
        new DeployRoleStack(app, "OttoDeployRoleStack",
                props("The role GitHub Actions deploys Otto as"));
        app.synth();
    }

    private static StackProps props(String description) {
        return StackProps.builder()
                .description(description)
                .env(Environment.builder()
                        .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                        .region(REGION)
                        .build())
                .build();
    }
}
