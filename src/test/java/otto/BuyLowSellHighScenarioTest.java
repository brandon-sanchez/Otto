package otto;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.harness.Fixtures;
import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.StoredRecords;
import otto.harness.WireSeamTest;
import otto.nflverse.NflverseFeedService;
import otto.records.DecisionRecord;
import otto.records.DecisionRecords;
import otto.records.Outcome;
import otto.records.RunKind;
import otto.storage.OttoJson;
import otto.telegram.TelegramWebhook;
import otto.telegram.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Buy-low and sell-high players by chat, judged by code and checked
 * before the user reads them.
 *
 * <p>The usage is real: ffopportunity's 2026 weeks 1 to 3 for seven
 * players, nothing after week 3, beside every running back, receiver and
 * tight end of its 2025 season, and nflverse's real schedule with its
 * starting quarterbacks and head coaches. The league is a 12-team
 * Superflex PPR redraft league; the FantasyCalc values and trends are
 * made up for the scenario. In 2025 George Pickens finished WR5,
 * Jameson Williams WR12, Courtland Sutton WR11, Saquon Barkley RB14 and
 * Colston Loveland TE12 in ffopportunity's full-PPR points.
 *
 * <ul>
 *   <li>George Pickens: his xFP held at 97% of 2025's but he scored 4.6
 *       points a game below it. A buy-low, and his value is down 18%.
 *   <li>Jameson Williams: xFP at 97% of 2025's and 3.4 below it. A buy-low.
 *   <li>Courtland Sutton: xFP down 22%: a partial bounce-back.
 *   <li>Saquon Barkley: xFP down 34%: a role decline, though his value is
 *       down 25%.
 *   <li>Trey McBride: usage up on 2025 and his value down 15%: a buy-low
 *       on price alone.
 *   <li>Jaxon Smith-Njigba, on the user's team: 12.5 points a game above
 *       his xFP of 22.2. A sell-high, and his value is up 30%.
 *   <li>Colston Loveland: xFP down 31%, a role decline. Chicago's week 3
 *       starter was not its week 1 starter; whether that also rules him
 *       out turns on Chicago's newest depth chart, because a new QB1 is
 *       the team's starter, not a backup.
 * </ul>
 */
class BuyLowSellHighScenarioTest extends WireSeamTest {

    private static final String USER_ID = "777001";
    private static final String LEAGUE_ID = "1389748403805650944";
    private static final String TOOL = "find_buy_low_sell_high";

    private static final String PICKENS = "8137";
    private static final String JAMESON_WILLIAMS = "8148";
    private static final String BARKLEY = "4866";
    private static final String SUTTON = "5045";
    private static final String MCBRIDE = "8130";
    private static final String SMITH_NJIGBA = "9488";
    private static final String LOVELAND = "12517";

    private static final String NARRATION = "Here are your buy-lows and sell-highs.";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private TelegramWebhook webhook;

    @Autowired
    private DecisionRecords decisionRecords;

    @Autowired
    private OttoProperties properties;

    private void theThirdWeekIsOnDisk(String directory) {
        theThirdWeekIsOnDisk(directory, "nflverse/depth-charts-2026.csv");
    }

    private void theThirdWeekIsOnDisk(String directory, String depthCharts) {
        theThirdWeekIsOnDisk(directory, depthCharts, () -> { });
    }

    /**
     * @param sleeperStubs stubs that replace the default Sleeper ones
     *        before the Check reads them
     */
    private void theThirdWeekIsOnDisk(String directory, String depthCharts,
            Runnable sleeperStubs) {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH, directory, "players-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH, "sleeper/rosters-buy-sell.json",
                "rosters-v1");
        sleeperStubs.run();
        NflverseStubs.healthy(nflverse);
        NflverseStubs.stubCsv(nflverse, NflverseStubs.DEPTH_2026_PATH, depthCharts);
        NflverseStubs.stubCsv(nflverse, NflverseStubs.SCHEDULE_PATH,
                "nflverse/games-buy-sell.csv");
        NflverseStubs.stubCsv(nflverse, NflverseStubs.EXPECTED_POINTS_2026_PATH,
                "nflverse/ep-weekly-2026-buy-sell.csv");
        NflverseStubs.stubCsv(nflverse, NflverseStubs.EXPECTED_POINTS_2025_PATH,
                "nflverse/ep-weekly-2025-skill.csv");
        NflverseStubs.stubCsv(nflverse, NflverseStubs.PLAYER_IDS_PATH,
                "nflverse/db-playerids-buy-sell.csv");
        fantasyCalc.stubFor(get(urlPathEqualTo("/values/current")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(Fixtures.read("fantasycalc/values-buy-sell.json"))));
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "An alert.");

        checkRunner.runCheck();
        feeds.updateIfDue();

        llm.resetAll();
        telegram.resetRequests();
        OutboundStubs.telegramOk(telegram);
    }

    private void theThirdWeekIsOnDisk() {
        theThirdWeekIsOnDisk("sleeper/players-nfl-buy-sell.json");
    }

    private void ask(String text) {
        assertThat(webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage(text)))
                .isEqualTo(WebhookResult.OK);
    }

    private static String answer(String message, String... players) {
        return """
                {"message": %s, "move": null, "players": [%s], "claims": [], "comparisons": []}
                """.formatted(OutboundStubs.jsonString(message), String.join(", ",
                List.of(players).stream().map(id -> "\"" + id + "\"").toList()));
    }

    private List<DecisionRecord> records() {
        return StoredRecords.of(decisionRecords, properties.storageDir(), USER_ID, LEAGUE_ID,
                RunKind.BUY_LOW_SELL_HIGH);
    }

    private Map<String, Map<String, String>> evidence() {
        return evidence(RunKind.BUY_LOW_SELL_HIGH);
    }

    private Map<String, Map<String, String>> evidence(RunKind kind) {
        JsonNode messages = StoredRecords.of(decisionRecords, properties.storageDir(), USER_ID,
                        LEAGUE_ID, kind).getLast().exchanges().getFirst().request()
                .path("messages");
        try {
            JsonNode bundle = OttoJson.MAPPER.readTree(messages.get(1).path("content").asText());
            Map<String, Map<String, String>> items = new HashMap<>();
            bundle.path("evidence").forEach(item -> {
                Map<String, String> facts = new HashMap<>();
                item.path("facts").properties()
                        .forEach(fact -> facts.put(fact.getKey(), fact.getValue().asText()));
                items.put(item.path("id").asText(), facts);
            });
            return items;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void theUserReads(String message) {
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing(OutboundStubs.jsonString(message))));
    }

    private static final String SCAN_MESSAGE = "George Pickens is a buy-low: his xFP is down "
            + "just 3% from last season, but he is scoring 4.6 points a game below what his "
            + "usage is worth, and his value is down 18% this month while his targets held. "
            + "Jameson Williams is a buy-low too, scoring 3.4 points a game below his usage. "
            + "Trey McBride is a buy-low on price: his value is down 15% while his usage held. "
            + "On your team, Jaxon Smith-Njigba is a sell-high: he is 12.5 points a game above "
            + "his usage, so expect about 22.2 a game from here.";

    @Test
    void aLeagueScanFindsBuyLowsOnOtherTeamsAndSellHighsOnTheUsersTeam() {
        theThirdWeekIsOnDisk();
        OutboundStubs.llmCallsToolThenPhrases(llm, TOOL, "{}", NARRATION);
        OutboundStubs.llmAdvises(llm, answer(SCAN_MESSAGE, PICKENS, JAMESON_WILLIAMS, MCBRIDE,
                SMITH_NJIGBA));

        ask("who should I buy low, and is anyone on my team a sell-high?");

        theUserReads(SCAN_MESSAGE);
        DecisionRecord record = records().getLast();
        assertThat(record.outcome()).isEqualTo(new Outcome.Sent(SCAN_MESSAGE));
        assertThat(record.candidates())
                .extracting(candidate -> candidate.playerId() + " " + candidate.reason())
                .containsExactly(PICKENS + " BUY_LOW", JAMESON_WILLIAMS + " BUY_LOW",
                        MCBRIDE + " MARKET_BUY_LOW", SMITH_NJIGBA + " SELL_HIGH");
        Map<String, Map<String, String>> evidence = evidence();
        assertThat(evidence.get("buysell-" + PICKENS))
                .containsEntry("call", "buy-low")
                .containsEntry("lastSeasonFinish", "WR5")
                .containsEntry("expectedPointsChange", "his xFP a game is down 3% from last season")
                .containsEntry("pointsVersusUsage",
                        "4.6 points a game below what his usage is worth")
                .containsEntry("usageHeld", "yes")
                .containsEntry("rosteredBy", "GridironGoblin");
        assertThat(evidence.get("buysell-market-" + PICKENS))
                .containsEntry("direction", "down")
                .containsEntry("change", "18%")
                .containsEntry("window", "the last 30 days");
        assertThat(evidence.get("buysell-" + MCBRIDE)).containsEntry("call", "buy-low on price");
        assertThat(evidence.get("buysell-" + SMITH_NJIGBA))
                .containsEntry("call", "sell-high")
                .containsEntry("levelToExpect", "22.2 points a game, his xFP this season")
                .containsEntry("pointsVersusUsage",
                        "12.5 points a game above what his usage is worth");
    }

    @Test
    void aValueDropWithFallingUsageIsARoleDeclineAndNeverABuyLow() {
        theThirdWeekIsOnDisk();
        String message = "Saquon Barkley is a role decline, not a buy-low: his xFP a game is "
                + "down 34% from last season. His value is down 25% this month, but his usage "
                + "fell with it, so that price is fair.";
        OutboundStubs.llmCallsToolThenPhrases(llm, TOOL, "{\"player\":\"Saquon Barkley\"}",
                NARRATION);
        OutboundStubs.llmAdvises(llm, answer(message, BARKLEY));

        ask("is Saquon Barkley a buy-low?");

        theUserReads(message);
        assertThat(records().getLast().candidates())
                .extracting(candidate -> candidate.reason())
                .containsExactly("ROLE_DECLINE");
        Map<String, Map<String, String>> evidence = evidence();
        assertThat(evidence.get("buysell-" + BARKLEY))
                .containsEntry("call", "role decline")
                .containsEntry("expectedPointsChange",
                        "his xFP a game is down 34% from last season");
        assertThat(evidence.get("buysell-market-" + BARKLEY))
                .containsEntry("direction", "down")
                .containsEntry("change", "25%")
                .containsEntry("meaning", "his value fell and so did his usage, so the lower "
                        + "price is fair and makes him no bargain");
    }

    @Test
    void aPlayerWhoseUsageSlippedTenToTwentyFivePercentIsAPartialBounceBack() {
        theThirdWeekIsOnDisk();
        String message = "Courtland Sutton is a partial bounce-back: his xFP a game is down 22% "
                + "from last season, so expect only part of his old level back.";
        OutboundStubs.llmCallsToolThenPhrases(llm, TOOL, "{\"player\":\"Courtland Sutton\"}",
                NARRATION);
        OutboundStubs.llmAdvises(llm, answer(message, SUTTON));

        ask("should I buy Courtland Sutton?");

        theUserReads(message);
        assertThat(evidence().get("buysell-" + SUTTON))
                .containsEntry("call", "partial bounce-back")
                .containsEntry("expectedPointsChange",
                        "his xFP a game is down 22% from last season");
    }

    @Test
    void askingWhetherToSellAPlayerGivesHisXfpAsTheLevelToExpect() {
        theThirdWeekIsOnDisk();
        String message = "Sell Jaxon Smith-Njigba high: he is scoring 34.7 points a game, 12.5 "
                + "above what his usage is worth, so expect about 22.2 a game from here.";
        OutboundStubs.llmCallsToolThenPhrases(llm, TOOL,
                "{\"player\":\"Jaxon Smith-Njigba\"}", NARRATION);
        OutboundStubs.llmAdvises(llm, answer(message, SMITH_NJIGBA));

        ask("should I sell Jaxon Smith-Njigba?");

        theUserReads(message);
        assertThat(evidence().get("buysell-" + SMITH_NJIGBA))
                .containsEntry("call", "sell-high")
                .containsEntry("recentTouchdowns",
                        "6 touchdowns on 3.0 expected over his last 3 games")
                .doesNotContainKey("ruledOutAsBuyLow");
    }

    @Test
    void anInjuryTagRulesOutABuyLow() {
        theThirdWeekIsOnDisk("sleeper/players-nfl-buy-sell-pickens-questionable.json");
        String message = "George Pickens is not a buy-low right now because he is listed as "
                + "Questionable.";
        OutboundStubs.llmCallsToolThenPhrases(llm, TOOL, "{\"player\":\"George Pickens\"}",
                NARRATION);
        OutboundStubs.llmAdvises(llm, answer(message, PICKENS));

        ask("is George Pickens a buy-low?");

        assertThat(evidence().get("buysell-" + PICKENS))
                .containsEntry("call", "neither a buy-low nor a sell-high")
                .containsEntry("ruledOutAsBuyLow", "he is listed as Questionable");
    }

    @Test
    void aPermanentSwitchToTheChartedStarterIsNotABackupQuarterback() {
        theThirdWeekIsOnDisk("sleeper/players-nfl-buy-sell.json",
                "nflverse/depth-charts-buy-sell-new-starter.csv");
        String message = "Colston Loveland is a role decline, not a buy-low: his xFP a game "
                + "is down 31% from last season.";
        OutboundStubs.llmCallsToolThenPhrases(llm, TOOL, "{\"player\":\"Colston Loveland\"}",
                NARRATION);
        OutboundStubs.llmAdvises(llm, answer(message, LOVELAND));

        ask("is Colston Loveland a buy-low?");

        assertThat(evidence().get("buysell-" + LOVELAND))
                .containsEntry("call", "role decline")
                .doesNotContainKey("ruledOutAsBuyLow");
    }

    @Test
    void aOneGameFillInForTheChartedStarterIsABackupQuarterback() {
        theThirdWeekIsOnDisk("sleeper/players-nfl-buy-sell.json",
                "nflverse/depth-charts-buy-sell-fill-in.csv");
        String message = "Colston Loveland is not a buy-low while Chicago starts a backup "
                + "quarterback.";
        OutboundStubs.llmCallsToolThenPhrases(llm, TOOL, "{\"player\":\"Colston Loveland\"}",
                NARRATION);
        OutboundStubs.llmAdvises(llm, answer(message, LOVELAND));

        ask("is Colston Loveland a buy-low?");

        assertThat(evidence().get("buysell-" + LOVELAND))
                .containsEntry("ruledOutAsBuyLow",
                        "his team started a backup quarterback in its latest game");
    }

    @Test
    void anAnswerWithANumberTheEvidenceLacksIsCorrectedOnceThenWithheld() {
        theThirdWeekIsOnDisk();
        String wrong = "Jaxon Smith-Njigba is a sell-high: expect about 15.0 points a game.";
        OutboundStubs.llmCallsToolThenPhrases(llm, TOOL,
                "{\"player\":\"Jaxon Smith-Njigba\"}", NARRATION);
        OutboundStubs.llmAdvises(llm, answer(wrong, SMITH_NJIGBA));

        ask("should I sell Jaxon Smith-Njigba?");

        llm.verify(2, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("legalMoves")));
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("15.0")));
        assertThat(records().getLast().outcome()).isInstanceOf(Outcome.Withheld.class);
    }

    /**
     * The same week with every player projected and GridironGoblin's
     * week-1 trade on record, so a trade with him can be priced.
     */
    private void theThirdWeekIsOnDiskWithProjections() {
        theThirdWeekIsOnDisk("sleeper/players-nfl-buy-sell.json",
                "nflverse/depth-charts-2026.csv", () -> {
                    SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH,
                            "sleeper/projections-buy-sell-trades.json", "projections-trades");
                    SleeperStubs.stubJson(sleeper, SleeperStubs.TRANSACTIONS_WEEK_1_PATH,
                            "sleeper/transactions-offers-trade.json", "transactions-trade");
                });
    }

    @Test
    void aBuyLowIsATradeTargetAndASellHighIsTheChipThatPaysForHim() {
        theThirdWeekIsOnDiskWithProjections();
        OutboundStubs.llmCallsToolThenPhrases(llm, "suggest_trades",
                "{\"shape\":\"1-for-1\",\"count\":1}", NARRATION);
        OutboundStubs.llmAdvises(llm, answer("x"));

        ask("find me a 1-for-1 trade");

        Map<String, Map<String, String>> evidence = evidence(RunKind.TRADE_IDEAS);
        assertThat(evidence.get("offer-1"))
                .containsEntry("sendTo", "GridironGoblin")
                .containsEntry("youGet", "George Pickens")
                .containsEntry("youGive", "Jaxon Smith-Njigba");
        assertThat(evidence.get("buysell-" + PICKENS))
                .containsEntry("call", "buy-low")
                .containsEntry("rosteredBy", "GridironGoblin");
        assertThat(evidence.get("buysell-" + SMITH_NJIGBA)).containsEntry("call", "sell-high");
    }
}
