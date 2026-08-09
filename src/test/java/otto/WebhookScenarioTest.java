package otto;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.events.EventLog;
import otto.events.EventType;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.telegram.TelegramWebhook;
import otto.telegram.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
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

    /** Any chat that is not the one the assistant serves. */
    private static final long FOREIGN_CHAT_ID = 9999;

    @Autowired
    private CheckRunner checkRunner;

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

    /**
     * The secret token guards the endpoint; the chat gate guards the
     * identity. Anybody who holds the secret can reach this seam, so a
     * button tap from another chat must act on no Alert of the user's.
     * It is still acknowledged: Telegram offers a callback_query again
     * until it gets an answerCallbackQuery.
     */
    @Test
    void aButtonTapFromAnotherChatActsOnNothingAndIsStillAcknowledged() {
        runHealthyBaselineCheck();
        runDeclineCheck();
        OutboundStubs.telegramCallbackAnswered(telegram);

        WebhookResult result = webhook.handle(WEBHOOK_SECRET,
                OutboundStubs.callbackTap(FOREIGN_CHAT_ID, "done:1"));

        assertThat(result).isEqualTo(WebhookResult.OK);
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.ANSWER_CALLBACK_PATH)));
        assertThat(eventLog.all().stream()
                .filter(event -> event.type() == EventType.USER_ACTION)).isEmpty();
    }

    /**
     * The same tap from the chat the assistant serves does the work, so
     * the gate reads the chat and not some other part of the update.
     */
    @Test
    void aButtonTapFromTheConfiguredChatStillActsOnTheAlert() {
        runHealthyBaselineCheck();
        runDeclineCheck();
        OutboundStubs.telegramCallbackAnswered(telegram);

        WebhookResult result = webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap("done:1"));

        assertThat(result).isEqualTo(WebhookResult.OK);
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.ANSWER_CALLBACK_PATH)));
        assertThat(eventLog.all().stream()
                .filter(event -> event.type() == EventType.USER_ACTION)
                .map(event -> event.key()))
                .containsExactly("action:done:1");
    }

    /**
     * Telegram leaves the message out of a tap on one too old to quote,
     * and the sender it still names is a user, not a chat. A tap that
     * names no chat therefore acts on nothing, whoever sent it.
     */
    @Test
    void aButtonTapThatNamesNoChatActsOnNothingAndIsStillAcknowledged() {
        runHealthyBaselineCheck();
        runDeclineCheck();
        OutboundStubs.telegramCallbackAnswered(telegram);

        WebhookResult result = webhook.handle(WEBHOOK_SECRET, """
                {
                  "update_id": 200,
                  "callback_query": {
                    "id": "cb-1",
                    "from": {"id": %d, "is_bot": false, "first_name": "Brandon"},
                    "data": "done:1"
                  }
                }
                """.formatted(USER_CHAT_ID));

        assertThat(result).isEqualTo(WebhookResult.OK);
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.ANSWER_CALLBACK_PATH)));
        assertThat(eventLog.all().stream()
                .filter(event -> event.type() == EventType.USER_ACTION)).isEmpty();
    }

    /** An Alert with buttons for the tap tests to act on. */
    private void runHealthyBaselineCheck() {
        SleeperStubs.healthyInSeason(sleeper);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "McCaffrey is Out. Bench him.");
        checkRunner.runCheck();
    }

    private void runDeclineCheck() {
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-mccaffrey-out.json", "players-v2");
        checkRunner.runCheck();
    }
}
