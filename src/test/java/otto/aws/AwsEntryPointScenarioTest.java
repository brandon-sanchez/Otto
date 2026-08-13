package otto.aws;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the deployed entry points make of what AWS hands them, before
 * any of it reaches the seams the local build already covers.
 */
class AwsEntryPointScenarioTest {

    private final WebhookHandler webhook = new WebhookHandler();

    @Test
    void everyScheduleNamesAJobTheBuildHas() {
        assertThat(ScheduledJob.of("check")).isEqualTo(ScheduledJob.CHECK);
        assertThat(ScheduledJob.of("nflverse-feeds")).isEqualTo(ScheduledJob.NFLVERSE_FEEDS);
        assertThat(ScheduledJob.of("defense-versus-position"))
                .isEqualTo(ScheduledJob.DEFENSE_VERSUS_POSITION);
    }

    @Test
    void aScheduleThatNamesAJobThisBuildLacksIsRefused() {
        assertThatThrownBy(() -> ScheduledJob.of("waiver-board"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("waiver-board");
    }

    @Test
    void theSecretTokenIsReadWhateverTheRuntimeCallsTheHeader() {
        assertThat(webhook.secretToken(request(
                Map.of("x-telegram-bot-api-secret-token", "shh"), "{}", false)))
                .contains("shh");
        assertThat(webhook.secretToken(request(
                Map.of("X-Telegram-Bot-Api-Secret-Token", "shh"), "{}", false)))
                .contains("shh");
    }

    @Test
    void aCallWithNoSecretTokenCarriesNone() {
        assertThat(webhook.secretToken(request(Map.of(), "{}", false))).isEmpty();
        assertThat(webhook.secretToken(request(null, "{}", false))).isEmpty();
    }

    @Test
    void anEncodedBodyIsDecodedBeforeTheSeamSeesIt() {
        String update = "{\"update_id\":7}";
        String encoded = Base64.getEncoder()
                .encodeToString(update.getBytes(StandardCharsets.UTF_8));

        assertThat(webhook.body(request(Map.of(), encoded, true))).isEqualTo(update);
        assertThat(webhook.body(request(Map.of(), update, false))).isEqualTo(update);
        assertThat(webhook.body(request(Map.of(), null, false))).isEmpty();
    }

    @Test
    void aForwardedAlarmSaysWhatItIsAbout() {
        assertThat(new AlarmForwarderHandler().text(sns("ALARM: otto-check-heartbeat")))
                .contains("otto-check-heartbeat")
                .contains("CloudWatch");
    }

    @Test
    void anAlarmWithNoSubjectStillReachesTheUser() {
        assertThat(new AlarmForwarderHandler().text(sns(null)))
                .contains("CloudWatch");
    }

    /**
     * The laptop must never reach for Parameter Store. The parameter
     * path is what turns the lookup on, so an environment without one
     * adds nothing and calls nothing.
     */
    @Test
    void withNoParameterPathNoSecretsAreLookedUp() {
        MockEnvironment environment = new MockEnvironment();

        new SsmSecrets().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getPropertySources().contains("otto-ssm-parameters")).isFalse();
    }

    /**
     * The secrets reader is found by Spring Boot, not by anything that
     * imports it, so a wrong key or a stale interface would go unnoticed
     * until a deployed function could not read a token.
     */
    @Test
    void theSecretsReaderIsRegisteredUnderTheKeySpringBootReads() throws Exception {
        Properties factories = new Properties();
        try (InputStream registration =
                     getClass().getResourceAsStream("/META-INF/spring.factories")) {
            assertThat(registration).as("META-INF/spring.factories").isNotNull();
            factories.load(registration);
        }

        assertThat(factories.getProperty(EnvironmentPostProcessor.class.getName()))
                .contains(SsmSecrets.class.getName());
        assertThat(EnvironmentPostProcessor.class).isAssignableFrom(SsmSecrets.class);
    }

    private APIGatewayV2HTTPEvent request(Map<String, String> headers, String body,
            boolean encoded) {
        return APIGatewayV2HTTPEvent.builder()
                .withHeaders(headers)
                .withBody(body)
                .withIsBase64Encoded(encoded)
                .build();
    }

    private SNSEvent.SNS sns(String subject) {
        return new SNSEvent.SNS().withSubject(subject).withMessage("{}");
    }
}
