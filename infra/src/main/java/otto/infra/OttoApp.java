package otto.infra;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

/**
 * The CDK entry point. One app, one stack, one region: the assistant
 * serves one league for one user, and a second stage would be a second
 * thing to keep alive for no one's benefit.
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
        new OttoStack(app, "OttoStack", StackProps.builder()
                .description("Otto, the fantasy football assistant")
                .env(Environment.builder()
                        .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                        .region(REGION)
                        .build())
                .build());
        app.synth();
    }
}
