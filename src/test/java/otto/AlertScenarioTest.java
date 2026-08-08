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

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

class AlertScenarioTest extends WireSeamTest {

    private static final String PHRASE =
            "McCaffrey is Out for Sunday. Bench him and start a healthy RB.";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private EventLog eventLog;

    private void runHealthyBaselineCheck() {
        SleeperStubs.healthyInSeason(sleeper);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, PHRASE);
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
    void aStarterStatusDeclineSendsOnePhrasedTelegramAlert() {
        runHealthyBaselineCheck();
        runDeclineCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.chat_id", equalTo("4242")))
                .withRequestBody(matchingJsonPath("$.text", equalTo(PHRASE))));

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Christian McCaffrey"))
                .withRequestBody(containing("HIGH")));

        Event alert = eventLog.all().stream()
                .filter(event -> event.type() == EventType.ALERT_SENT)
                .findFirst().orElseThrow();
        assertThat(alert.key()).isEqualTo("alert:snapshot-diff:status:4034:ACTIVE->OUT");
        assertThat(alert.facts())
                .containsEntry("player", "Christian McCaffrey")
                .containsEntry("confidence", "HIGH");
        assertThat(alert.facts().get("pros")).isNotBlank();
        assertThat(alert.facts().get("cons")).isNotBlank();
    }

    @Test
    void aTransientTelegramFailureRetriesTheAlertOnTheNextCheck() {
        SleeperStubs.healthyInSeason(sleeper);
        OutboundStubs.llmPhrases(llm, PHRASE);
        telegram.stubFor(com.github.tomakehurst.wiremock.client.WireMock
                .post(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock
                        .aResponse().withStatus(500)));
        checkRunner.runCheck();
        runDeclineCheck();
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));

        // Telegram recovers; the next Check delivers the same Alert once.
        telegram.resetAll();
        OutboundStubs.telegramOk(telegram);
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.PLAYERS_PATH, "players-v2");
        checkRunner.runCheck();
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(PHRASE))));

        // And never again after that.
        clock.advance(Duration.ofSeconds(61));
        checkRunner.runCheck();
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void theSameDeclineNeverAlertsTwice() {
        runHealthyBaselineCheck();
        runDeclineCheck();

        // The player recovers: that transition is its own Alert.
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl.json", "players-v3");
        OutboundStubs.llmPhrases(llm, "McCaffrey is back to Active.");
        checkRunner.runCheck();

        // Then he declines the same way again: no second decline Alert.
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-mccaffrey-out.json", "players-v4");
        checkRunner.runCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(PHRASE))));
        telegram.verify(2, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }
}
