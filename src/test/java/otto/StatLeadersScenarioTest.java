package otto;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.advice.ToolAnswer;
import otto.ask.LeagueRankLookup;
import otto.ask.StatLeaders;
import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.nflverse.NflverseFeedService;
import otto.telegram.TelegramWebhook;
import otto.telegram.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lists of the league leaders in one stat, asked for in chat.
 *
 * The leaders weekly stats hold twelve pass catchers over weeks 1 and 2:
 * every third one a tight end. Leader 1's WOPR is 0.80 in week 1 and
 * each next one's 0.05 lower, and every player's is 0.02 higher in
 * week 2, so the season order runs 1 to 12.
 */
class StatLeadersScenarioTest extends WireSeamTest {

    private static final String TOP_TEN = """
            Here are the top 10 players in WOPR for weeks 1 to 2 of 2026:
            1. Leader TE 1 (TE), 0.81 WOPR in 2 games
            2. Leader WR 2 (WR), 0.76 WOPR in 2 games
            3. Leader WR 3 (WR), 0.71 WOPR in 2 games
            4. Leader TE 4 (TE), 0.66 WOPR in 2 games
            5. Leader WR 5 (WR), 0.61 WOPR in 2 games
            6. Leader WR 6 (WR), 0.56 WOPR in 2 games
            7. Leader TE 7 (TE), 0.51 WOPR in 2 games
            8. Leader WR 8 (WR), 0.46 WOPR in 2 games
            9. Leader WR 9 (WR), 0.41 WOPR in 2 games
            10. Leader TE 10 (TE), 0.36 WOPR in 2 games""";

    private static final String TOOL_RESULT = "\"role\":\"tool\"";

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private LeagueRankLookup ranks;

    @Autowired
    private TelegramWebhook webhook;

    private void leadersStored() {
        SleeperStubs.healthyInSeason(sleeper);
        NflverseStubs.healthy(nflverse);
        OutboundStubs.telegramOk(telegram);
        NflverseStubs.stubCsv(nflverse, NflverseStubs.STATS_2026_PATH,
                "nflverse/stats-player-week-leaders.csv");
        feeds.updateIfDue();
    }

    @Test
    void theTopTenInAnAdvancedStatComeAcrossEveryPositionWithTheirSamples() {
        leadersStored();

        ToolAnswer<StatLeaders.Answer> answer = ranks.leaders("wopr", "all", null, null, null);

        assertThat(answer.facts().message()).isEqualTo(TOP_TEN);
        assertThat(answer.facts().leaders()).hasSize(10).first().satisfies(first -> {
            assertThat(first.rank()).isEqualTo("1st");
            assertThat(first.sample()).isEqualTo("2 games");
        });
    }

    @Test
    void aListCoversOnePositionOverASpanOfWeeks() {
        leadersStored();

        StatLeaders.Answer tightEnds = ranks.leaders("WOPR", "tight ends", 5, 2, 2).facts();

        assertThat(tightEnds.message()).isEqualTo("""
                Only 4 tight ends have WOPR for week 2 of 2026, so here they all are:
                1. Leader TE 1, 0.82 WOPR in 1 game
                2. Leader TE 4, 0.67 WOPR in 1 game
                3. Leader TE 7, 0.52 WOPR in 1 game
                4. Leader TE 10, 0.37 WOPR in 1 game""");
    }

    @Test
    void aSeasonOnlyStatOrAnUnknownStatIsSaidPlainly() {
        leadersStored();

        assertThat(ranks.leaders("pressured_rate", "QB", null, 2, 3).unavailable())
                .isEqualTo("I can only rank pressured rate over the whole season so far.");
        assertThat(ranks.leaders("vibes", null, null, null, null).unavailable())
                .contains("vibes").contains("air_yards");
    }

    @Test
    void theListReachesTheUserWordForWord() {
        leadersStored();
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_stat_leaders",
                "{\"stat\":\"wopr\",\"position\":\"all\"}", "Here are your leaders.");

        assertThat(webhook.handle(WEBHOOK_SECRET,
                OutboundStubs.textMessage("Who leads all players in WOPR?")))
                .isEqualTo(WebhookResult.OK);

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing(OutboundStubs.jsonString(TOP_TEN))));
        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(TOOL_RESULT)));
    }
}
