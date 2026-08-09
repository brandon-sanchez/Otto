package otto;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.nflverse.DefenseVersusPositionBuilder;
import otto.nflverse.NflverseFeedService;
import otto.telegram.TelegramWebhook;
import otto.telegram.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Player analysis by chat: a comparison of two players and the latest
 * news on any player.
 *
 * The fixture week is 2026 week 2. Josh Jacobs (GB) projects 16.5 and
 * meets DET, the softest running-back matchup in the table at 32.5
 * points allowed per game. James Cook (BUF) projects 13.0 and meets
 * MIA, the toughest at 13.0. Java computes all of it; the model only
 * words the answer.
 */
class PlayerAnalysisScenarioTest extends WireSeamTest {

    private static final String COMPARE_PHRASE =
            "Jacobs, and it is not close: +3.5 projected and the softer matchup.";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private DefenseVersusPositionBuilder defenseBuilder;

    @Autowired
    private TelegramWebhook webhook;

    /**
     * Everything an analysis question reads: a stored Snapshot, the
     * nflverse feeds, and the nightly defense-versus-position table.
     * The Alert traffic from the Check is not what these scenarios
     * observe, so the LLM and Telegram journals reset before the Ask.
     */
    private void aWeekOfDataOnDisk() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH,
                "sleeper/projections-edge.json", "projections-v1");
        SleeperStubs.stubNews(sleeper, "5850", "sleeper/news-5850.json");
        SleeperStubs.stubNews(sleeper, "8138", "sleeper/news-8138.json");
        SleeperStubs.stubNews(sleeper, "4046", "sleeper/news-4046.json");
        NflverseStubs.healthy(nflverse);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "An alert about the bench edge.");

        checkRunner.runCheck();
        feeds.updateIfDue();
        defenseBuilder.build();

        llm.resetAll();
        telegram.resetRequests();
        sleeper.resetRequests();
    }

    private void ask(String text) {
        assertThat(webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage(text)))
                .isEqualTo(WebhookResult.OK);
    }

    private void modelCalls(String tool, String arguments, String phrase) {
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, tool, arguments, phrase);
    }

    @Test
    void aComparisonCarriesProjectionsMatchupStatusAndNewsForBothPlayers() {
        aWeekOfDataOnDisk();
        modelCalls("compare_players",
                "{\"first\":\"Josh Jacobs\",\"second\":\"James Cook\"}", COMPARE_PHRASE);

        ask("Jacobs or Cook this week?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                // Projections in league scoring, and the edge between them.
                .withRequestBody(containing("16.5"))
                .withRequestBody(containing("13.0"))
                .withRequestBody(containing("+3.5"))
                // Matchup context from the defense-versus-position table.
                .withRequestBody(containing("DET"))
                .withRequestBody(containing("32.5"))
                .withRequestBody(containing("MIA"))
                // Status and role, joined to nflverse through the id map.
                .withRequestBody(containing("ACTIVE"))
                .withRequestBody(containing("RB1"))
                // Recent news for both players.
                .withRequestBody(containing("Full go for Week 2"))
                .withRequestBody(containing("Limited Wednesday with ankle"))
                // Never Sleeper's or nflverse's own pre-computed points.
                .withRequestBody(notContaining("99.9")));

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing(COMPARE_PHRASE)));
    }

    @Test
    void aComparisonReachesPlayersTheUserDoesNotRoster() {
        aWeekOfDataOnDisk();
        modelCalls("compare_players",
                "{\"first\":\"Josh Jacobs\",\"second\":\"Puka Nacua\"}",
                "Jacobs. Nacua is on bye.");

        ask("Jacobs or Nacua?");

        // Nacua is on no roster in this league, and the Rams have no game
        // this week. A player who cannot play is a certainty, not an
        // estimate, so the call rides at High.
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Puka Nacua"))
                .withRequestBody(containing("on bye"))
                .withRequestBody(containing("HIGH")));
    }

    @Test
    void aComparisonWithoutTheDefenseTableStillAnswersAndSaysWhatItCannotSee() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH,
                "sleeper/projections-edge.json", "projections-v1");
        SleeperStubs.stubNews(sleeper, "5850", "sleeper/news-5850.json");
        SleeperStubs.stubNews(sleeper, "8138", "sleeper/news-8138.json");
        NflverseStubs.healthy(nflverse);
        nflverse.stubFor(get(urlEqualTo(NflverseStubs.STATS_RELEASE_PATH))
                .willReturn(aResponse().withStatus(503)));
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "An alert.");
        checkRunner.runCheck();
        feeds.updateIfDue();
        defenseBuilder.build();
        llm.resetAll();
        telegram.resetRequests();

        modelCalls("compare_players",
                "{\"first\":\"Josh Jacobs\",\"second\":\"James Cook\"}",
                "Jacobs on projection. I cannot see matchup data right now.");

        ask("Jacobs or Cook?");

        // The projections and the news still answer the question; the
        // missing matchup is named, never quietly skipped.
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("16.5"))
                .withRequestBody(containing("cannot see"))
                .withRequestBody(containing("defense-versus-position")));
    }

    @Test
    void playerNewsIsAlwaysFetchedLive() {
        aWeekOfDataOnDisk();
        modelCalls("get_player_news", "{\"player\":\"Patrick Mahomes\"}",
                "Mahomes has no designation for Week 2.");

        ask("any news on Mahomes?");
        // The canned tool-call sequence is a scenario on the fake model
        // server; rearm it so the second question routes to the tool too.
        llm.resetScenarios();
        ask("and now?");

        // News is the one source with no cache: two questions, two reads.
        sleeper.verify(2, getRequestedFor(urlPathEqualTo("/players/nfl/4046/news")));
        llm.verify(2, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("No designation for Week 2")));
    }

    @Test
    void otherToolsReadCachedDataUntilTheCadenceIntervalPasses() {
        aWeekOfDataOnDisk();
        modelCalls("get_league_settings", "{}", "Superflex, full PPR.");

        ask("what are my league settings?");

        // The Check polled the league document moments ago and nothing
        // can be fresher than the Freshness Ceiling, so the Ask spends no
        // request at all.
        sleeper.verify(0, getRequestedFor(urlEqualTo(SleeperStubs.LEAGUE_PATH)));

        // Once the cached copy ages past the cadence interval, the tool
        // goes back to the wire.
        clock.advance(Duration.ofSeconds(61));
        llm.resetAll();
        modelCalls("get_league_settings", "{}", "Superflex, full PPR.");
        ask("and again?");

        sleeper.verify(1, getRequestedFor(urlEqualTo(SleeperStubs.LEAGUE_PATH)));
    }
}
