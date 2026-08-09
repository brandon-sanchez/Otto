package otto;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.events.EventLog;
import otto.events.EventType;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.telegram.TelegramWebhook;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The verification loop: after an Alert, a later Check with a fresh
 * Snapshot confirms once that the problem is gone - whatever fix the
 * user chose - and the loop closes in the Event Log. Done with no
 * visible roster change gets one gentle note after 20 minutes; no
 * reply gets nothing beyond the normal Lock Ladder.
 */
class VerificationScenarioTest extends WireSeamTest {

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private TelegramWebhook webhook;

    @Autowired
    private EventLog eventLog;

    private void runHealthyBaselineCheck() {
        SleeperStubs.healthyInSeason(sleeper);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.telegramCallbackAnswered(telegram);
        OutboundStubs.llmPhrases(llm, "McCaffrey is Out. Bench him.");
        checkRunner.runCheck();
    }

    /** McCaffrey is ruled Out: one Alert, buttons attached. */
    private void runDeclineCheck() {
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-mccaffrey-out.json", "players-v2");
        checkRunner.runCheck();
    }

    /** The user has benched McCaffrey and started Jacobs in his slot. */
    private void runFixedRosterCheck() {
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.PLAYERS_PATH, "players-v2");
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-mccaffrey-benched.json", "rosters-v2");
        checkRunner.runCheck();
    }

    private void runUnchangedCheck(String rostersEtag) {
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.PLAYERS_PATH, "players-v2");
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.ROSTERS_PATH, rostersEtag);
        checkRunner.runCheck();
    }

    @Test
    void anyFixClosesTheLoopWithOneConfirmation() {
        runHealthyBaselineCheck();
        runDeclineCheck();

        // The Alert Check itself never confirms: verification always
        // waits for a Snapshot fresher than the Alert.
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));

        // The user fixed the lineup in Sleeper without tapping anything.
        runFixedRosterCheck();

        telegram.verify(2, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.contains(
                "verified:snapshot-diff:status:4034:ACTIVE->OUT")).isTrue();
        assertThat(eventLog.contains(
                "verified:legality:2026-w2:slot1:4034:locked-out")).isTrue();

        // Confirmed once, never again.
        runUnchangedCheck("rosters-v2");
        telegram.verify(2, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void doneWithNoVisibleRosterChangeGetsOneGentleNoteAfterTwentyMinutes() {
        runHealthyBaselineCheck();
        runDeclineCheck();
        webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap("done:1"));

        // One minute later nothing has changed: too early for the note.
        runUnchangedCheck("rosters-v1");
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));

        // Twenty minutes after the tap the roster still shows no change.
        clock.advance(Duration.ofMinutes(20));
        runUnchangedCheck("rosters-v1");
        telegram.verify(2, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.all().stream()
                .filter(event -> event.type() == EventType.NOTE_SENT)
                .filter(event -> event.key().equals("note:done:1")))
                .hasSize(1);

        // The note never repeats; only the normal Lock Ladder remains.
        runUnchangedCheck("rosters-v1");
        telegram.verify(2, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void aDoneTapFollowedByARealFixConfirmsAndNeverNags() {
        runHealthyBaselineCheck();
        runDeclineCheck();
        webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap("done:1"));

        runFixedRosterCheck();
        telegram.verify(2, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));

        // Twenty minutes on: the loop is closed, so no gentle note.
        clock.advance(Duration.ofMinutes(20));
        runUnchangedCheck("rosters-v2");
        telegram.verify(2, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.all().stream()
                .filter(event -> event.type() == EventType.NOTE_SENT)).isEmpty();
    }

    @Test
    void theGentleNoteNeverFiresAfterLock() {
        runHealthyBaselineCheck();

        // McCaffrey is ruled Out just before his game: the Alert goes
        // out inside the final window (SF kicks off 2026-09-21T00:20Z).
        clock.set(Instant.parse("2026-09-20T23:55:00Z"));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-mccaffrey-out.json", "players-v2");
        checkRunner.runCheck();
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));

        // Done tapped 15 minutes before lock: the 20-minute patience
        // window ends after kickoff, so the note is never due.
        clock.set(Instant.parse("2026-09-21T00:05:00Z"));
        webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap("done:1"));

        clock.set(Instant.parse("2026-09-21T00:26:00Z"));
        runUnchangedCheck("rosters-v1");
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.all().stream()
                .filter(event -> event.type() == EventType.NOTE_SENT)).isEmpty();
    }

    @Test
    void noReplyMeansNoPingsBeyondTheLadder() {
        runHealthyBaselineCheck();
        runDeclineCheck();

        // An hour of unchanged Checks, no tap: the assistant stays quiet.
        for (int minute = 0; minute < 60; minute += 5) {
            clock.advance(Duration.ofMinutes(5));
            sleeper.resetAll();
            SleeperStubs.allNotModified(sleeper);
            SleeperStubs.stubNotModified(sleeper, SleeperStubs.PLAYERS_PATH, "players-v2");
            checkRunner.runCheck();
        }
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }
}
