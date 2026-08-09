package otto;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.events.EventLog;
import otto.harness.WireSeamTest;
import otto.telegram.TelegramWebhook;
import otto.telegram.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Telegram webhook entry point: Telegram calls it with every
 * update and sends the configured secret token in the
 * X-Telegram-Bot-Api-Secret-Token header. A request that does not
 * carry the right token is rejected before any parsing.
 */
class WebhookScenarioTest extends WireSeamTest {

    private static final String BENIGN_UPDATE = """
            {"update_id": 100, "message": {"message_id": 7, "text": "hello"}}
            """;

    @Autowired
    private TelegramWebhook webhook;

    @Autowired
    private EventLog eventLog;

    @Test
    void aRequestWithoutTheSecretTokenIsRejected() {
        assertThat(webhook.handle(null, BENIGN_UPDATE)).isEqualTo(WebhookResult.FORBIDDEN);
        assertThat(webhook.handle("wrong-secret", BENIGN_UPDATE))
                .isEqualTo(WebhookResult.FORBIDDEN);

        telegram.verify(0, anyRequestedFor(anyUrl()));
        assertThat(eventLog.all()).isEmpty();
    }

    @Test
    void aRequestWithTheRightSecretTokenIsAccepted() {
        assertThat(webhook.handle(WEBHOOK_SECRET, BENIGN_UPDATE)).isEqualTo(WebhookResult.OK);
    }
}
