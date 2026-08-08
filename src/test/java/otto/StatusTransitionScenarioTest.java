package otto;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.events.Event;
import otto.events.EventLog;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every status transition on the user's rostered players produces an
 * Alert - up or down, IR and bench included - deduped by the Event Log.
 */
class StatusTransitionScenarioTest extends WireSeamTest {

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private EventLog eventLog;

    private void runHealthyBaselineCheck() {
        SleeperStubs.healthyInSeason(sleeper);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Roster status update.");
        checkRunner.runCheck();
    }

    private void runPlayersFileCheck(String fixture, String etag) {
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH, fixture, etag);
        checkRunner.runCheck();
    }

    @Test
    void benchDeclinesAndIrMovesEachAlertOnce() {
        runHealthyBaselineCheck();

        runPlayersFileCheck("sleeper/players-nfl-jacobs-questionable.json", "players-v2");
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        Event benchDecline = eventLog.all().stream()
                .filter(event -> event.key()
                        .equals("alert:snapshot-diff:status:5850:ACTIVE->QUESTIONABLE"))
                .findFirst().orElseThrow();
        assertThat(benchDecline.facts())
                .containsEntry("player", "Josh Jacobs")
                .containsEntry("confidence", "MEDIUM");
        assertThat(benchDecline.facts().get("action")).contains("No lineup change needed");

        runPlayersFileCheck("sleeper/players-nfl-jacobs-ir.json", "players-v3");
        telegram.verify(2, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.contains("alert:snapshot-diff:status:5850:QUESTIONABLE->IR"))
                .isTrue();

        // The same state on the next Check adds nothing.
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.PLAYERS_PATH, "players-v3");
        checkRunner.runCheck();
        telegram.verify(2, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void anImprovementAlertsAsAMediumStatusUpdate() {
        runHealthyBaselineCheck();
        runPlayersFileCheck("sleeper/players-nfl-mccaffrey-out.json", "players-v2");
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));

        runPlayersFileCheck("sleeper/players-nfl-mccaffrey-questionable.json", "players-v3");

        telegram.verify(2, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        Event improvement = eventLog.all().stream()
                .filter(event -> event.key()
                        .equals("alert:snapshot-diff:status:4034:OUT->QUESTIONABLE"))
                .findFirst().orElseThrow();
        assertThat(improvement.facts()).containsEntry("confidence", "MEDIUM");
        assertThat(improvement.facts().get("action")).contains("improved from OUT to QUESTIONABLE");
    }
}
