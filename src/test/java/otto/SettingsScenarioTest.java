package otto;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.lineup.PositionCutoffs;
import otto.settings.SettingsStore;
import otto.settings.Trigger;
import otto.storage.JsonStore;
import otto.telegram.TelegramWebhook;
import otto.telegram.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Settings by chat: the trigger toggles, the edge threshold, the
 * Notable Player cutoffs and the mute list, all changed and read
 * through the same tool. There is no settings screen by design, so what
 * the chat cannot reach the user cannot change.
 */
class SettingsScenarioTest extends WireSeamTest {

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private TelegramWebhook webhook;

    @Autowired
    private SettingsStore settings;

    @Autowired
    private OttoProperties properties;

    private void ask(String text) {
        assertThat(webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage(text)))
                .isEqualTo(WebhookResult.OK);
    }

    /** One chat turn that routes to manage_settings and narrates it. */
    private void settingsAsk(String arguments, String question) {
        llm.resetAll();
        OutboundStubs.llmCallsToolThenPhrases(llm, "manage_settings", arguments, "Done.");
        ask(question);
    }

    private void checkWithAnEdge() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH,
                "sleeper/projections-edge.json", "projections-v1");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.telegramCallbackAnswered(telegram);
        OutboundStubs.llmPhrases(llm, "Lineup notice.");
        checkRunner.runCheck();
    }

    @Test
    void settingsAreReadableByChat() {
        OutboundStubs.telegramOk(telegram);
        settingsAsk("{\"action\":\"show\"}", "what are your settings?");

        // Every trigger, the edge threshold, the Notable Player cutoffs
        // and the quiet-hours field the spec keeps empty in v1. Every
        // name the view shows is one the tool accepts back.
        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("status_transition"))
                .withRequestBody(containing("lineup_legality"))
                .withRequestBody(containing("bench_edge"))
                .withRequestBody(containing("watchlist"))
                .withRequestBody(containing("waiver"))
                .withRequestBody(containing("league_activity"))
                .withRequestBody(containing("edge_threshold"))
                .withRequestBody(containing("notable_qb"))
                .withRequestBody(containing("notable_te"))
                .withRequestBody(containing("quiet_hours")));
    }

    @Test
    void theEdgeThresholdChangedByChatChangesWhatAlerts() {
        OutboundStubs.telegramOk(telegram);
        settingsAsk("{\"action\":\"set\",\"name\":\"edge_threshold\",\"value\":\"5.0\"}",
                "only tell me about edges over 5 points");
        assertThat(settings.current().edgeThreshold()).isEqualTo(5.0);

        // The fixture edge is 3.5 points, under the new threshold.
        telegram.resetRequests();
        checkWithAnEdge();
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));

        settingsAsk("{\"action\":\"set\",\"name\":\"edge_threshold\",\"value\":\"1.0\"}",
                "back to one point");
        telegram.resetRequests();
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        OutboundStubs.llmPhrases(llm, "Start Jacobs over Cook.");
        checkRunner.runCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void aTriggerSwitchedOffByChatStopsItsAlerts() {
        OutboundStubs.telegramOk(telegram);
        settingsAsk("{\"action\":\"set\",\"name\":\"bench_edge\",\"value\":\"off\"}",
                "stop telling me about bench edges");
        assertThat(settings.current().enabled(Trigger.BENCH_EDGE)).isFalse();

        telegram.resetRequests();
        checkWithAnEdge();
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));

        settingsAsk("{\"action\":\"set\",\"name\":\"bench_edge\",\"value\":\"on\"}",
                "edges are fine again");
        telegram.resetRequests();
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        OutboundStubs.llmPhrases(llm, "Start Jacobs over Cook.");
        checkRunner.runCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void notablePlayerCutoffsChangeByChat() {
        OutboundStubs.telegramOk(telegram);
        settingsAsk("{\"action\":\"set\",\"name\":\"notable_rb\",\"value\":\"30\"}",
                "count the top 30 running backs as notable");

        // The one the user moved changes; the rest keep the spec's line.
        assertThat(settings.current().notablePlayerCutoffs())
                .isEqualTo(new PositionCutoffs(12, 30, 24, 12));
    }

    /** The spec keeps the field and leaves it empty until quiet hours ship. */
    @Test
    void quietHoursStayEmptyInV1() {
        OutboundStubs.telegramOk(telegram);
        settingsAsk("{\"action\":\"set\",\"name\":\"quiet_hours\",\"value\":\"22:00-07:00\"}",
                "no alerts after ten at night");

        assertThat(settings.current().quietHours()).isEmpty();
        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("quiet hours are not part of v1")));
    }

    @Test
    void theMuteListShowsMutesSetByButtonAndByChat() {
        // A status Alert arrives and the user taps Mute under it.
        SleeperStubs.healthyInSeason(sleeper);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.telegramCallbackAnswered(telegram);
        OutboundStubs.llmPhrases(llm, "McCaffrey is out.");
        checkRunner.runCheck();

        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-mccaffrey-out.json", "players-v2");
        checkRunner.runCheck();
        webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap("mute:1"));

        // And silences a whole class by chat.
        settingsAsk("{\"action\":\"mute\",\"name\":\"bench_edge\"}", "mute bench edges");

        settingsAsk("{\"action\":\"show\"}", "what have I muted?");
        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Christian McCaffrey news"))
                .withRequestBody(containing("bench_edge alerts")));

        // The list reads back in the words the tool accepts, so an
        // unmute can quote it rather than translate it.
        settingsAsk("{\"action\":\"unmute\",\"name\":\"bench_edge alerts\"}",
                "unmute bench edges");
        settingsAsk("{\"action\":\"unmute\",\"name\":\"Christian McCaffrey news\"}",
                "unmute McCaffrey");
        settingsAsk("{\"action\":\"show\"}", "what have I muted?");
        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("bench_edge alerts")));
        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Christian McCaffrey news")));
    }

    @Test
    void aPlayerMutedByChatSilencesHisNews() {
        SleeperStubs.healthyInSeason(sleeper);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Baseline.");
        checkRunner.runCheck();

        settingsAsk("{\"action\":\"mute\",\"name\":\"Christian McCaffrey\"}",
                "mute McCaffrey news");

        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-mccaffrey-questionable.json", "players-v2");
        telegram.resetRequests();
        OutboundStubs.llmPhrases(llm, "Should not be sent.");
        checkRunner.runCheck();

        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void anUnknownSettingIsRefusedRatherThanGuessed() {
        OutboundStubs.telegramOk(telegram);
        settingsAsk("{\"action\":\"set\",\"name\":\"vibes\",\"value\":\"high\"}",
                "turn the vibes up");

        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("I have no setting called vibes")));
    }

    /** Settings are a stored document: a cold reader sees the changes. */
    @Test
    void settingsSurviveRestarts() {
        OutboundStubs.telegramOk(telegram);
        settingsAsk("{\"action\":\"set\",\"name\":\"edge_threshold\",\"value\":\"2.5\"}",
                "two and a half points");

        SettingsStore coldRead = new SettingsStore(new JsonStore(properties), properties);
        assertThat(coldRead.current().edgeThreshold()).isEqualTo(2.5);
        assertThat(coldRead.current().enabled(Trigger.WATCHLIST)).isTrue();
    }
}
