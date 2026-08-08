package otto;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.events.EventLog;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.telegram.TelegramWebhook;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ignore declines one Recommendation: "I am keeping him". Follow-ups
 * for that problem stop - no final warning, no confirmation - and the
 * problem revives only when the status gets worse or the lineup is
 * illegal. The 30-minute illegal-lineup warning fires even when
 * ignored. McCaffrey's SF game kicks off 2026-09-21T00:20Z.
 */
class IgnoreScenarioTest extends WireSeamTest {

    private static final Instant INSIDE_WINDOW = Instant.parse("2026-09-20T23:55:00Z");

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
    void ignoreStopsTheFinalWarningForAnImpairedStarter() {
        runHealthyBaselineCheck();
        runPlayersFileCheck("sleeper/players-nfl-mccaffrey-questionable.json", "players-v2");
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));

        webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap("ignore:1"));

        // Inside the 30-minute window: without the Ignore this warns
        // (see LockLadderScenarioTest); ignored, it stays quiet.
        clock.set(INSIDE_WINDOW);
        runUnchangedCheck("players-v2");
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.contains("alert:final:2026-w2:4034")).isFalse();
    }

    @Test
    void theIllegalLineupWarningFiresEvenWhenIgnored() {
        runHealthyBaselineCheck();
        runPlayersFileCheck("sleeper/players-nfl-mccaffrey-out.json", "players-v2");
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));

        webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap("ignore:1"));

        // An Out starter is an illegal lineup: the warning still fires.
        clock.set(INSIDE_WINDOW);
        runUnchangedCheck("players-v2");
        telegram.verify(2, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.contains("alert:final:2026-w2:4034")).isTrue();
    }

    @Test
    void aWorseStatusRevivesAnIgnoredProblem() {
        runHealthyBaselineCheck();
        runPlayersFileCheck("sleeper/players-nfl-mccaffrey-questionable.json", "players-v2");
        webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap("ignore:1"));

        // Questionable turns into Out: that is a new, worse problem
        // and it alerts despite the earlier Ignore.
        runPlayersFileCheck("sleeper/players-nfl-mccaffrey-out.json", "players-v3");
        telegram.verify(2, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));

        // And the final warning is live again too.
        clock.set(INSIDE_WINDOW);
        runUnchangedCheck("players-v3");
        telegram.verify(3, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.contains("alert:final:2026-w2:4034")).isTrue();
    }

    @Test
    void anIgnoredProblemNeverConfirms() {
        runHealthyBaselineCheck();
        runPlayersFileCheck("sleeper/players-nfl-mccaffrey-out.json", "players-v2");
        webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap("ignore:1"));

        // The user benches him anyway: the declined recommendation
        // closes silently, with no confirmation message.
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.PLAYERS_PATH, "players-v2");
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-mccaffrey-benched.json", "rosters-v2");
        checkRunner.runCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.all().stream()
                .filter(event -> event.key().startsWith("verified:"))).isEmpty();
    }
}
