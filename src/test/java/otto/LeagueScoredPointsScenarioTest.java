package otto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.nflverse.NflverseFeedService;
import otto.telegram.TelegramWebhook;
import otto.telegram.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The owner of one half-PPR league that pays tight ends half a point
 * more per catch asks what Isaiah Likely scored. Otto answers in that
 * league's scoring, from Sleeper's own score when the league's matchups
 * carry one and from his Sleeper stat line otherwise, never in
 * ffopportunity's full PPR.
 */
class LeagueScoredPointsScenarioTest extends WireSeamTest {

    private static final String LIKELY_ID = "8150";
    private static final String TOOL = "get_player_stats";
    private static final String NARRATION = "Here are his numbers.";

    private static final String LAST_WEEK = "Isaiah Likely scored <b>16.0</b> points in week 5.";

    private static final String PER_GAME = "Isaiah Likely is averaging <b>9.5</b> points per game "
            + "over 4 games.\n🟢 <b>TE2</b> of 4 tight ends in Raising Canes Demon scoring "
            + "(38.0 points, weeks 1 to 5 of 2026)";

    private static final String LAST_WEEK_CALL = "{\"player\":\"Isaiah Likely\",\"weeksAgo\":1,"
            + "\"asked\":\"fantasy points\",\"fantasyPoints\":true}";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private TelegramWebhook webhook;

    @BeforeEach
    void aHalfPprTePremiumLeagueAndTheRavensSeason() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH,
                "sleeper/league-market-1qb-half-ppr.json", "league-half-ppr");
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-likely.json", "players-v1");
        SleeperStubs.likelysPlayedWeeks(sleeper);
        for (int week = 1; week <= 5; week++) {
            leagueScores(week, "{}");
        }
        NflverseStubs.isaiahLikelysSeason(nflverse);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "An alert.");

        checkRunner.runCheck();
        feeds.updateIfDue();

        llm.resetAll();
        telegram.resetRequests();
        OutboundStubs.telegramOk(telegram);
    }

    /** Serves the league's week as one matchup whose players scored as given. */
    private static void leagueScores(int week, String playersPoints) {
        sleeper.stubFor(get(urlEqualTo(SleeperStubs.LEAGUE_PATH + "/matchups/" + week))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                [{"roster_id": 2, "matchup_id": 1, "starters": [],
                                  "starters_points": [], "players_points": %s}]
                                """.formatted(playersPoints))));
    }

    private void ask(String text, String arguments, String... answers) {
        OutboundStubs.llmCallsToolThenPhrases(llm, TOOL, arguments, NARRATION);
        String[] advised = new String[answers.length];
        for (int index = 0; index < answers.length; index++) {
            advised[index] = """
                    {"message": %s, "move": null, "players": ["%s"], "claims": []}
                    """.formatted(OutboundStubs.jsonString(answers[index]), LIKELY_ID);
        }
        OutboundStubs.llmAdvises(llm, advised);
        assertThat(webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage(text)))
                .isEqualTo(WebhookResult.OK);
    }

    private static void theEvidenceHolds(String... facts) {
        var request = postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("legalMoves"));
        for (String fact : facts) {
            request = request.withRequestBody(containing(fact));
        }
        llm.verify(request);
    }

    private static void theOwnerReadsOnly(String message) {
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing(OutboundStubs.jsonString(message))));
    }

    /** Returns one evidence fact as the request carries it: JSON inside a JSON string. */
    private static String fact(String key, String value) {
        return "\\\"%s\\\":\\\"%s\\\"".formatted(key, value);
    }

    @Test
    void lastWeeksPointsComeInTheLeaguesOwnScoringWithoutAskingWhichLeague() {
        ask("How many fantasy points did Isaiah Likely score last week?", LAST_WEEK_CALL,
                LAST_WEEK);

        theOwnerReadsOnly(LAST_WEEK);
        theEvidenceHolds(fact("league", "Raising Canes Demon"),
                fact("scoring", "half PPR, tight ends +0.5 per catch"),
                fact("fantasyPoints", "16.0"),
                fact("scoredBy", "his Sleeper stat line priced in this league's scoring"));
        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("legalMoves"))
                .withRequestBody(containing(fact("fantasyPoints", "17.3"))));
    }

    @Test
    void ffopportunitysFullPprPointsFailTheFactCheck() {
        ask("How many fantasy points did Isaiah Likely score last week?", LAST_WEEK_CALL,
                "Isaiah Likely scored <b>17.3</b> points in week 5.");

        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("failed its fact check twice")));
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("17.3")));
    }

    @Test
    void sleepersOwnScoreForTheLeagueWinsOverThePricedLine() {
        leagueScores(5, "{\"%s\": 16.4}".formatted(LIKELY_ID));
        String official = "Isaiah Likely scored <b>16.4</b> points in week 5.";

        ask("How many fantasy points did Isaiah Likely score last week?", LAST_WEEK_CALL,
                official);

        theOwnerReadsOnly(official);
        theEvidenceHolds(fact("fantasyPoints", "16.4"),
                fact("scoredBy", "Sleeper's own score in this league"));
    }

    @Test
    void sleepersZeroForARosteredPlayerIsNotAGameWhenNoStatLineShowsHimPlaying() {
        sleeper.stubFor(get(urlEqualTo("/v1/stats/nfl/regular/2026/5"))
                .willReturn(aResponse().withStatus(503)));
        leagueScores(5, "{\"%s\": 0.0}".formatted(LIKELY_ID));
        String unknown = "I can't see Isaiah Likely's points for week 5 yet.";

        ask("How many fantasy points did Isaiah Likely score last week?", LAST_WEEK_CALL,
                unknown);

        theOwnerReadsOnly(unknown);
        theEvidenceHolds("\\\"fantasyPoints\\\":\\\"unknown: Sleeper's week 5 stats could not "
                + "be read\\\"");
    }

    @Test
    void aSeasonAverageComesWithHisRankAmongTightEndsInTheLeaguesScoring() {
        ask("What's Isaiah Likely averaging per game this season, and where does that rank?",
                "{\"player\":\"Isaiah Likely\",\"asked\":\"points per game and rank\","
                        + "\"fantasyPoints\":true}",
                PER_GAME);

        theOwnerReadsOnly(PER_GAME);
        theEvidenceHolds(fact("totalPoints", "38.0"), fact("gamesScored", "4 games"),
                fact("pointsPerGame", "9.5"),
                fact("pointsByWeek", "week 1 6.0, week 2 8.0, week 4 8.0, week 5 16.0"),
                fact("rank", "2nd"));
    }
}
