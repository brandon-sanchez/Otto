package otto;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.alerts.MuteStore;
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
 * Mute silences a class of Alerts or one player's news until the user
 * unmutes it. A Mute controls noise only: it never cancels a
 * Recommendation, so a muted player's legality problem still alerts.
 */
class MuteScenarioTest extends WireSeamTest {

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private TelegramWebhook webhook;

    @Autowired
    private EventLog eventLog;

    @Autowired
    private MuteStore muteStore;

    private void runBaselineCheckWithProjections(String projectionsFixture) {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH,
                projectionsFixture, "projections-v1");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.telegramCallbackAnswered(telegram);
        OutboundStubs.llmPhrases(llm, "Lineup notice.");
        checkRunner.runCheck();
    }

    private void runHealthyBaselineCheck() {
        SleeperStubs.healthyInSeason(sleeper);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.telegramCallbackAnswered(telegram);
        OutboundStubs.llmPhrases(llm, "Lineup notice.");
        checkRunner.runCheck();
    }

    private void runProjectionsCheck(String fixture, String etag) {
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH, fixture, etag);
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
    void aClassMuteSilencesEdgeAlertsUntilUnmuted() {
        runBaselineCheckWithProjections("sleeper/projections-edge.json");
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));

        webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap("mute:1"));

        // A brand-new edge appears: the class is muted, so silence.
        runProjectionsCheck("sleeper/projections-te-edge.json", "projections-v2");
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.contains("alert:edge:2026-w2:6813-over-1466")).isFalse();

        // Unmuted, the still-open edge alerts on the next Check.
        muteStore.unmute("class:edge");
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.PROJECTIONS_PATH, "projections-v2");
        checkRunner.runCheck();

        telegram.verify(2, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.contains("alert:edge:2026-w2:6813-over-1466")).isTrue();
    }

    @Test
    void aPlayerNewsMuteSilencesThatPlayerOnly() {
        runHealthyBaselineCheck();
        runPlayersFileCheck("sleeper/players-nfl-jacobs-questionable.json", "players-v2");
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));

        webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap("mute:1"));

        // Jacobs declines further: his news is muted, silence.
        runPlayersFileCheck("sleeper/players-nfl-jacobs-ir.json", "players-v3");
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.contains(
                "alert:snapshot-diff:status:5850:QUESTIONABLE->IR")).isFalse();

        // Another player's news is untouched by the mute.
        runPlayersFileCheck("sleeper/players-nfl-mccaffrey-out.json", "players-v4");
        telegram.verify(2, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.contains(
                "alert:snapshot-diff:status:4034:ACTIVE->OUT")).isTrue();
    }

    @Test
    void aMuteNeverCancelsARecommendation() {
        runHealthyBaselineCheck();
        runPlayersFileCheck("sleeper/players-nfl-mccaffrey-questionable.json", "players-v2");
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));

        // The tap mutes McCaffrey's news: a transition Alert is news.
        webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap("mute:1"));

        // He is ruled Out: the muted transition stays quiet, but the
        // legality Recommendation still arrives - a Mute never cancels
        // a Recommendation.
        runPlayersFileCheck("sleeper/players-nfl-mccaffrey-out.json", "players-v3");
        telegram.verify(2, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.contains(
                "alert:legality:2026-w2:slot1:4034:locked-out")).isTrue();
        assertThat(eventLog.contains(
                "alert:snapshot-diff:status:4034:QUESTIONABLE->OUT")).isFalse();
    }
}
