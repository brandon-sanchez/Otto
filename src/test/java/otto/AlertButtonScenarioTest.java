package otto;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.telegram.TelegramWebhook;
import otto.telegram.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acting on an Alert takes one touch: every Alert carries inline
 * Done, Ignore, and Mute buttons, and a tap arrives as a
 * callback_query on the webhook, is recorded, and is acknowledged.
 */
class AlertButtonScenarioTest extends WireSeamTest {

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private TelegramWebhook webhook;

    @Autowired
    private EventLog eventLog;

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

    @Test
    void anAlertCarriesDoneIgnoreAndMuteButtons() {
        runHealthyBaselineCheck();
        runDeclineCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath(
                        "$.reply_markup.inline_keyboard[0][0].callback_data", equalTo("done:1")))
                .withRequestBody(matchingJsonPath(
                        "$.reply_markup.inline_keyboard[0][1].callback_data", equalTo("ignore:1")))
                .withRequestBody(matchingJsonPath(
                        "$.reply_markup.inline_keyboard[0][2].callback_data", equalTo("mute:1"))));

        // Every Event Log record of this outbound message carries the
        // short id the buttons reference.
        assertThat(eventLog.all().stream()
                .filter(event -> event.type() == EventType.ALERT_SENT)
                .map(event -> event.facts().get("alertId")))
                .isNotEmpty()
                .containsOnly("1");
    }

    @Test
    void aDoneTapIsRecordedAndAcknowledged() {
        runHealthyBaselineCheck();
        runDeclineCheck();
        OutboundStubs.telegramCallbackAnswered(telegram);

        WebhookResult result = webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap("done:1"));

        assertThat(result).isEqualTo(WebhookResult.OK);
        telegram.verify(1, postRequestedFor(
                urlEqualTo(OutboundStubs.ANSWER_CALLBACK_PATH))
                .withRequestBody(matchingJsonPath("$.callback_query_id", equalTo("cb-1"))));
        Event action = eventLog.all().stream()
                .filter(event -> event.type() == EventType.USER_ACTION)
                .findFirst().orElseThrow();
        assertThat(action.key()).isEqualTo("action:done:1");
        assertThat(action.facts())
                .containsEntry("action", "done")
                .containsEntry("alertId", "1")
                .containsEntry("playerId", "4034");
    }

    @Test
    void aTapOnAnUnknownAlertIdStillAnswersTheCallback() {
        runHealthyBaselineCheck();
        OutboundStubs.telegramCallbackAnswered(telegram);

        WebhookResult result = webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap("done:9"));

        assertThat(result).isEqualTo(WebhookResult.OK);
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.ANSWER_CALLBACK_PATH)));
        assertThat(eventLog.all().stream()
                .filter(event -> event.type() == EventType.USER_ACTION)).isEmpty();
    }
}
