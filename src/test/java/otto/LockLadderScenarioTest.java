package otto;

import java.time.Duration;
import java.time.Instant;

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
 * The Lock Ladder: one Alert on detect, one final warning inside the
 * half hour before that player's game lock, nothing after lock.
 * McCaffrey's SF game kicks off 2026-09-21T00:20Z in the fixtures.
 */
class LockLadderScenarioTest extends WireSeamTest {

    private static final Instant INSIDE_WINDOW = Instant.parse("2026-09-20T23:55:00Z");
    private static final Instant AFTER_LOCK = Instant.parse("2026-09-21T00:21:00Z");
    private static final Instant GAME_UNDERWAY = Instant.parse("2026-09-21T00:30:00Z");

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private EventLog eventLog;

    private void runHealthyBaselineCheck() {
        SleeperStubs.healthyInSeason(sleeper);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Check your lineup.");
        checkRunner.runCheck();
    }

    private void runPlayersFileCheck(String fixture, String etag) {
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH, fixture, etag);
        checkRunner.runCheck();
    }

    private void runUnchangedCheck(String playersEtag) {
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.PLAYERS_PATH, playersEtag);
        checkRunner.runCheck();
    }

    @Test
    void anOpenProblemGetsOneFinalWarningAndNothingAfterLock() {
        runHealthyBaselineCheck();
        runPlayersFileCheck("sleeper/players-nfl-mccaffrey-out.json", "players-v2");
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));

        // Inside the 30-minute window before SF's kickoff: one warning.
        clock.set(INSIDE_WINDOW);
        runUnchangedCheck("players-v2");
        telegram.verify(2, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        Event warning = eventLog.all().stream()
                .filter(event -> event.key().equals("alert:final:2026-w2:4034"))
                .findFirst().orElseThrow();
        assertThat(warning.facts())
                .containsEntry("finalWarning", "true")
                .containsEntry("minutesToLock", "25");

        // Still inside the window: the warning never repeats.
        clock.advance(Duration.ofMinutes(10));
        runUnchangedCheck("players-v2");
        telegram.verify(2, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));

        // After lock: nothing.
        clock.set(AFTER_LOCK);
        runUnchangedCheck("players-v2");
        telegram.verify(2, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void anImpairedStarterWithNoOtherProblemStillGetsTheFinalWarning() {
        runHealthyBaselineCheck();
        runPlayersFileCheck("sleeper/players-nfl-mccaffrey-questionable.json", "players-v2");
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));

        clock.set(INSIDE_WINDOW);
        runUnchangedCheck("players-v2");

        telegram.verify(2, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        Event warning = eventLog.all().stream()
                .filter(event -> event.key().equals("alert:final:2026-w2:4034"))
                .findFirst().orElseThrow();
        assertThat(warning.facts()).containsEntry("finalWarning", "true");
    }

    @Test
    void aProblemDetectedDuringTheGameWaitsUntilTheGameCompletes() {
        runHealthyBaselineCheck();

        // McCaffrey is ruled Out mid-game: unactionable, no Alert.
        clock.set(GAME_UNDERWAY);
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-mccaffrey-out.json", "players-v2");
        checkRunner.runCheck();
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));

        // The game completes about three hours later: the transition
        // still matters for next week and sends once; the burned
        // lineup slot never re-alerts.
        clock.advance(Duration.ofHours(3));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.PLAYERS_PATH, "players-v2");
        SleeperStubs.stubJson(sleeper, SleeperStubs.SCORES_PATH,
                "sleeper/scores-2026-2-sf-complete.json", "scores-v2");
        checkRunner.runCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.contains("alert:snapshot-diff:status:4034:ACTIVE->OUT")).isTrue();
        assertThat(eventLog.contains("alert:legality:2026-w2:slot1:4034:locked-out")).isFalse();
    }
}
