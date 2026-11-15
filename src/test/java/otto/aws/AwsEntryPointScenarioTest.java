package otto.aws;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import otto.storage.ConcurrentDocumentChange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the deployed entry points make of what AWS hands them, before
 * any of it reaches the seams the local build already covers.
 */
class AwsEntryPointScenarioTest {

    /** The webhook function's timeout in {@code OttoStack}, which OttoStackTest pins. */
    private static final Duration WEBHOOK_TIMEOUT = Duration.ofSeconds(60);

    /** What an answered Ask still needs after the model: its Telegram send and record. */
    private static final Duration SEND_ALLOWANCE = Duration.ofSeconds(15);

    /** The scheduled function's timeout in {@code OttoStack}, which OttoStackTest pins. */
    private static final Duration SCHEDULED_TIMEOUT = Duration.ofSeconds(300);

    /** What a Check still needs after its last model run: its sends and its stores. */
    private static final Duration STORE_ALLOWANCE = Duration.ofSeconds(45);

    private final WebhookHandler webhook = new WebhookHandler();

    @Test
    void everyScheduleNamesAJobTheBuildHas() {
        assertThat(ScheduledJob.of("check")).isEqualTo(ScheduledJob.CHECK);
        assertThat(ScheduledJob.of("nflverse-feeds")).isEqualTo(ScheduledJob.NFLVERSE_FEEDS);
        assertThat(ScheduledJob.of("defense-versus-position"))
                .isEqualTo(ScheduledJob.DEFENSE_VERSUS_POSITION);
        assertThat(ScheduledJob.of("research")).isEqualTo(ScheduledJob.RESEARCH);
    }

    @Test
    void anAskAnswersInsideTheWebhookFunctionsTimeoutWithTimeLeftToSendIt() {
        Duration askDeadline = DurationStyle.detectAndParse(
                applicationYaml().getProperty("otto.models.budgets.ask.deadline"));

        assertThat(askDeadline.plus(SEND_ALLOWANCE)).isLessThanOrEqualTo(WEBHOOK_TIMEOUT);
    }

    @Test
    void aChecksModelRunsEndInsideTheScheduledFunctionsTimeoutWithTimeLeftToStore() {
        Properties yaml = applicationYaml();
        Duration check = DurationStyle.detectAndParse(
                yaml.getProperty("otto.models.budgets.check"));
        Duration phrasing = DurationStyle.detectAndParse(
                yaml.getProperty("otto.models.budgets.phrasing.deadline"));

        assertThat(check.plus(STORE_ALLOWANCE)).isLessThanOrEqualTo(SCHEDULED_TIMEOUT);
        assertThat(check).as("a Check fits at least one phrasing run").isGreaterThan(phrasing);
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

    /**
     * The tap is the user's, and nothing else will retry it. He has
     * already seen the acknowledgement, so a dropped storage write
     * would leave him told that a Done he never stored had worked.
     */
    @Test
    void aTapWhoseStorageWasRefusedIsAskedForAgain() {
        APIGatewayV2HTTPResponse answer =
                webhook.recoverFrom(new ConcurrentDocumentChange("event-log"));

        assertThat(answer.getStatusCode())
                .as("any answer that is not a success makes Telegram send it again")
                .isEqualTo(503);
    }

    /**
     * A body this build cannot handle would fail the same way on every
     * retry, so it is let go rather than looped on.
     */
    @Test
    void anUpdateThisBuildCannotHandleIsNotAskedForAgain() {
        APIGatewayV2HTTPResponse answer =
                webhook.recoverFrom(new IllegalArgumentException("nonsense update"));

        assertThat(answer.getStatusCode()).isEqualTo(200);
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

    private static Properties applicationYaml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        return yaml.getObject();
    }
}
