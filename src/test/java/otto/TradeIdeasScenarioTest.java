package otto;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.harness.Fixtures;
import otto.harness.LineupHistory;
import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.StoredRecords;
import otto.harness.WireSeamTest;
import otto.nflverse.DefenseVersusPosition;
import otto.nflverse.NflverseFeedService;
import otto.nflverse.NflverseStore;
import otto.records.DecisionRecord;
import otto.records.DecisionRecords;
import otto.records.Outcome;
import otto.records.RunKind;
import otto.storage.JsonStore;
import otto.storage.OttoJson;
import otto.webhook.TelegramWebhook;
import otto.webhook.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * "What trades should I make?" by chat: named offers, each priced the
 * way evaluate_trade prices it, explained in one checked message.
 *
 * <p>The league is in week 12 of a regular season that ends in week 14,
 * priced from the trade scenario's projections. Four teams hold players.
 * The user starts Christian McCaffrey and nobody beside him at RB, so his
 * RB starters rank 4th of 4, and he holds six receivers. GridironGoblin
 * is 9-2, deep at RB and thin at WR and TE, and made a trade in week 5.
 * WaiverWizard has made no move all season, and EndZoneEnvy is 2-9.
 */
class TradeIdeasScenarioTest extends WireSeamTest {

    private static final Instant WEEK_12 = Instant.parse("2026-11-24T18:00:00Z");

    private static final Instant BEFORE_THE_LAST_GAME = Instant.parse("2026-12-07T19:00:00Z");
    private static final Instant AFTER_THE_LAST_GAME = Instant.parse("2026-12-08T14:00:00Z");

    private static final String USER_ID = "777001";
    private static final String LEAGUE_ID = "1389748403805650944";

    private static final String NARRATION = "Here are some trades.";

    private static final String BARKLEY = "4866";
    private static final String GOEDERT = "6813";
    private static final String KELCE = "1466";
    private static final String JEFFERSON = "6794";
    private static final String REAGOR = "6768";

    private static final String CHECKED = "Send GridironGoblin Dallas Goedert and Justin "
            + "Jefferson for Saquon Barkley. It's a slight edge for you on rest-of-season fit, "
            + "a gap of 8.2%, and on market value GridironGoblin gets 9500 for the 8200 he "
            + "gives.";

    private static final String WRONG_PARTNER = "Send GridironGoblin Dallas Goedert and Jalen "
            + "Reagor for Saquon Barkley.";

    private static final String NOT_ON_HIS_ROSTER = "Send GridironGoblin Dallas Goedert and "
            + "Justin Jefferson for Christian McCaffrey.";

    private static final String LAMB = "6786";
    private static final String TE_DEPTH_03 = "94003";
    private static final String WR_DEPTH_04 = "93004";

    private static final String TIGHT_END_OFFER = "Send WaiverWizard CeeDee Lamb for TE Depth "
            + "03 and WR Depth 04. It's a slight edge for you on rest-of-season fit, a gap of "
            + "9.4%.";

    private static final String TIGHT_END_DEPTH ="a 1-for-2: one of yours for two of his, "
            + "for depth at TE";

    private static final String PLAYOFF_WEEKS = "GridironGoblin is contending, and Justin "
            + "Jefferson gets a soft playoff run, facing CHI, DET and GB in weeks 15 to 17, "
            + "defenses that rank 25th of 32, 30th of 32 and 22nd of 32 against wide "
            + "receivers";

    private static final String CHECKED_WITH_PLAYOFF_WEEKS = CHECKED + " GridironGoblin is "
            + "contending, and Justin Jefferson gets a soft playoff run, facing CHI, DET and "
            + "GB in weeks 15 to 17, defenses that rank 25th of 32, 30th of 32 and 22nd of 32 "
            + "against wide receivers.";

    private static final String CLOSED = "Trades are closed for the season, so the waiver "
            + "wire is where your team gets better now.";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private TelegramWebhook webhook;

    @Autowired
    private DecisionRecords decisionRecords;

    @Autowired
    private OttoProperties properties;

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private JsonStore store;

    /** The league's week 6 transactions, none unless a test sets them before the league loads. */
    private String weekSixTransactions = "sleeper/transactions-none.json";

    private void leagueInWeek(String leagueFixture, String rostersFixture, String stateFixture,
            int week) {
        leagueInWeek(leagueFixture, rostersFixture, stateFixture, week, WEEK_12);
    }

    private void leagueInWeek(String leagueFixture, String rostersFixture, String stateFixture,
            int week, Instant now) {
        clock.set(now);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-team.json", "players-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH, leagueFixture, "league-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH, rostersFixture, "rosters-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.USERS_PATH,
                "sleeper/users-league.json", "users-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.STATE_PATH, stateFixture, "state-v1");
        for (int remaining = week; remaining <= 14; remaining++) {
            SleeperStubs.stubJson(sleeper, "/v1/projections/nfl/regular/2026/" + remaining,
                    "sleeper/projections-trade.json", "projections-w" + remaining);
            SleeperStubs.stubJson(sleeper, "/scores/nfl/regular/2026/" + remaining,
                    "sleeper/scores-trade-w" + remaining + ".json", "scores-w" + remaining);
        }
        for (int played = 1; played <= week; played++) {
            SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH + "/transactions/" + played,
                    played == 5 ? "sleeper/transactions-offers-trade.json"
                            : played == 6 ? weekSixTransactions
                            : "sleeper/transactions-none.json", "transactions-w" + played);
        }
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH + "/traded_picks",
                "sleeper/traded-picks-trade.json", "traded-picks-v1");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "A quiet week.");
        checkRunner.runCheck();
        llm.resetAll();
        telegram.resetRequests();
        OutboundStubs.telegramOk(telegram);
    }

    private void redraftLeague(String rostersFixture) {
        leagueInWeek("sleeper/league-in-season.json", rostersFixture,
                "sleeper/state-nfl-week12.json", 12);
        fantasyCalcAnswers("false", "fantasycalc/values-offers-redraft.json");
    }

    private static void fantasyCalcAnswers(String dynasty, String fixture) {
        fantasyCalc.stubFor(get(urlPathEqualTo("/values/current"))
                .withQueryParam("isDynasty", equalTo(dynasty))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(Fixtures.read(fixture))));
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

    private List<DecisionRecord> records(RunKind kind) {
        return StoredRecords.of(decisionRecords, properties.storageDir(), USER_ID, LEAGUE_ID,
                kind);
    }

    private Map<String, Map<String, String>> evidence(RunKind kind) {
        DecisionRecord record = records(kind).getLast();
        JsonNode messages = record.exchanges().getFirst().request().path("messages");
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

    private static String field(String name, String value) {
        return "\\\"%s\\\":\\\"%s\\\"".formatted(name, value);
    }

    private static void theUserReads(String message) {
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing(OutboundStubs.jsonString(message))));
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing(NARRATION)));
    }

    private void nextQuestion() {
        llm.resetAll();
        telegram.resetRequests();
        OutboundStubs.telegramOk(telegram);
        clock.advance(Duration.ofSeconds(1));
    }

    private Map<String, Map<String, String>> askForTrades(String arguments) {
        OutboundStubs.llmCallsToolThenPhrases(llm, "suggest_trades", arguments, NARRATION);
        OutboundStubs.llmAdvises(llm, answer(CHECKED, GOEDERT, JEFFERSON, BARKLEY));
        ask("what trades should I make and why?");
        return evidence(RunKind.TRADE_IDEAS);
    }

    @Test
    void anUpgradeIsATwoForOneThePartnerWouldAcceptAndEvaluateTradeAgrees() {
        redraftLeague("sleeper/rosters-offers.json");

        Map<String, Map<String, String>> evidence = askForTrades("{}");

        assertThat(evidence.get("offer-1"))
                .containsEntry("sendTo", "GridironGoblin")
                .containsEntry("youGet", "Saquon Barkley")
                .containsEntry("youGive", "Dallas Goedert and Justin Jefferson")
                .containsEntry("shape", "a 2-for-1: two of yours for one better player, to "
                        + "upgrade your RB starters")
                .containsEntry("yourEdge", "a slight edge for you on rest-of-season fit, a "
                        + "gap of 8.2%")
                .containsEntry("why1", "On market value GridironGoblin gets 9500 for the 8200 "
                        + "he gives")
                .containsEntry("why4", "GridironGoblin is contending, 9-2, 4 games clear of "
                        + "the first team out of the 6 playoff spots, with the most points and "
                        + "the 2nd-most valuable roster, so points now matter to him")
                .containsEntry("why5", "GridironGoblin has made 1 trade this season");
        assertThat(evidence).containsKeys("offer-2", "offer-3").doesNotContainKey("offer-4");
        assertThat(evidence.get("summary")).containsEntry("found", "3 offers");
        theUserReads(CHECKED);
        assertThat(records(RunKind.TRADE_IDEAS)).singleElement().satisfies(record ->
                assertThat(record.outcome()).isEqualTo(new Outcome.Sent(CHECKED)));

        nextQuestion();
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                "{\"manager\":\"GridironGoblin\",\"gets\":\"Saquon Barkley\","
                        + "\"gives\":\"Dallas Goedert, Justin Jefferson\"}", "Judged.");
        ask("should I trade Goedert and Jefferson for Barkley?");

        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(field("verdict", "slight edge")))
                .withRequestBody(containing(field("favours", "you")))
                .withRequestBody(containing(field("gap", "8.2%"))));
    }

    @Test
    void depthIsAOneForTwoAtThePositionWithNoCover() {
        redraftLeague("sleeper/rosters-offers-depth.json");

        Map<String, Map<String, String>> evidence = askForTrades("{}");

        assertThat(evidence.values()).anySatisfy(offer -> assertThat(offer)
                .containsEntry("shape", "a 1-for-2: one of yours for two of his, for depth "
                        + "at RB")
                .hasEntrySatisfying("youGet", names -> assertThat(names).contains(" and "))
                .hasEntrySatisfying("youGive", names -> assertThat(names)
                        .doesNotContain(" and ")));
    }

    @Test
    void aTradeOfMoreThanFourPiecesASideIsDeclinedInPlainWords() {
        redraftLeague("sleeper/rosters-offers.json");
        OutboundStubs.llmCallsToolThenPhrases(llm, "suggest_trades",
                "{\"shape\":\"9-for-9\"}", NARRATION);

        ask("build me a 9-for-9 trade");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("\"role\":\"tool\""))
                .withRequestBody(containing("I build trades of up to 4 players a side")));
        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("legalMoves")));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing(NARRATION)));
    }

    @Test
    void aFourForFourIsTheLargestShapeBuilt() {
        redraftLeague("sleeper/rosters-offers.json");
        OutboundStubs.llmCallsToolThenPhrases(llm, "suggest_trades",
                "{\"shape\":\"4-for-4\"}", NARRATION);

        ask("build me a 4-for-4 trade");

        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("I build trades of up to 4 players a side")));
    }

    @Test
    void anyShapeTheUserAsksForIsBuilt() {
        redraftLeague("sleeper/rosters-offers.json");

        Map<String, Map<String, String>> evidence = askForTrades("{\"shape\":\"3-for-2\"}");

        assertThat(evidence.get("summary")).containsEntry("shape",
                "each offer is a 3-for-2, as he asked");
        assertThat(evidence.get("offer-1"))
                .containsEntry("shape", "a 3-for-2, the shape you asked for")
                .hasEntrySatisfying("youGive", names -> assertThat(names.split(", | and "))
                        .hasSize(3))
                .hasEntrySatisfying("youGet", names -> assertThat(names.split(", | and "))
                        .hasSize(2));
    }

    @Test
    void aDynastyOfferCanSpendTheUsersDraftPicks() {
        leagueInWeek("sleeper/league-dynasty.json", "sleeper/rosters-offers.json",
                "sleeper/state-nfl-week12.json", 12);
        fantasyCalcAnswers("true", "fantasycalc/values-offers-dynasty.json");

        Map<String, Map<String, String>> evidence = askForTrades("{}");

        assertThat(evidence.get("offer-1"))
                .containsEntry("sendTo", "GridironGoblin")
                .containsEntry("youGet", "RB Depth 10")
                .containsEntry("youGive", "Jalen Reagor and 2028 2nd-round pick")
                .containsEntry("yourEdge", "about even on market value, leaning your way by "
                        + "2.4%")
                .containsEntry("market", "on market value you get 4200 and give 4100");
        assertThat(evidence.get("offer-1-pick3"))
                .containsEntry("pick", "2028 2nd-round pick")
                .containsEntry("nflDraft", "2nd round");
    }

    @Test
    void theUserCanAskForMoreIdeasThanThree() {
        redraftLeague("sleeper/rosters-offers.json");

        Map<String, Map<String, String>> evidence = askForTrades("{\"count\":5}");

        assertThat(evidence.get("summary")).containsEntry("asked", "5 trade ideas");
        assertThat(evidence).containsKeys("offer-4");
    }

    @Test
    void showMoreAsksHowManyThenSkipsTheIdeasAlreadyShown() {
        redraftLeague("sleeper/rosters-offers.json");
        Map<String, Map<String, String>> first = askForTrades("{}");

        nextQuestion();
        OutboundStubs.llmCallsToolThenPhrases(llm, "suggest_trades", "{\"shown\":3}",
                "How many more would you like?");
        ask("show more");

        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("ask him how many more he wants")));
        assertThat(records(RunKind.TRADE_IDEAS)).hasSize(1);

        nextQuestion();
        Map<String, Map<String, String>> more = askForTrades("{\"count\":2,\"shown\":3}");

        assertThat(more.get("summary")).containsEntry("asked",
                "2 more trade ideas after the 3 he has seen");
        List<String> seen = List.of("offer-1", "offer-2", "offer-3").stream()
                .map(id -> first.get(id).get("youGet") + " for " + first.get(id).get("youGive"))
                .toList();
        assertThat(more.get("offer-1").get("youGet") + " for "
                + more.get("offer-1").get("youGive")).isNotIn(seen);
    }

    @Test
    void onMondayBeforeTheDeadlineWeeksLastGameOffersSayWhenTradesClose() {
        deadlineWeekLeague(BEFORE_THE_LAST_GAME);
        scheduleStored("nflverse/games-deadline-week.csv");

        Map<String, Map<String, String>> evidence = askForTrades("{}");

        assertThat(evidence.get("trade-deadline")).containsEntry("tradeDeadline",
                "The trade deadline is this week. Trades close when week 13's last game "
                        + "ends, usually Monday night. A trade has to be proposed and accepted "
                        + "before then, and its review period can finish afterward.");
        assertThat(evidence).containsKey("offer-1");
    }

    private void scheduleStored(String fixture) {
        NflverseStubs.healthy(nflverse);
        NflverseStubs.stubCsv(nflverse, NflverseStubs.SCHEDULE_PATH, fixture);
        feeds.updateIfDue();
    }

    private void deadlineWeekLeague(Instant now) {
        leagueInWeek("sleeper/league-in-season.json", "sleeper/rosters-offers.json",
                "sleeper/state-nfl-week13.json", 13, now);
        fantasyCalcAnswers("false", "fantasycalc/values-offers-redraft.json");
    }

    private void theAnswerSaysTradesAreClosed(String reason) {
        OutboundStubs.llmCallsToolThenPhrases(llm, "suggest_trades", "{}", NARRATION);
        OutboundStubs.llmAdvises(llm, answer(CLOSED));

        ask("what trades should I make?");

        assertThat(records(RunKind.TRADE_IDEAS)).isEmpty();
        Map<String, Map<String, String>> evidence = evidence(RunKind.TEAM_WEAKNESSES);
        assertThat(evidence.get("trade-deadline").get("tradeDeadline")).startsWith(reason);
        assertThat(evidence.values()).noneSatisfy(facts ->
                assertThat(facts).containsKey("sendTo"));
        theUserReads(CLOSED);
    }

    @Test
    void onTuesdayMorningAfterTheDeadlineWeeksLastGameTradesAreClosed() {
        deadlineWeekLeague(AFTER_THE_LAST_GAME);
        scheduleStored("nflverse/games-deadline-week.csv");

        theAnswerSaysTradesAreClosed("the trade deadline passed after week 13");
    }

    @Test
    void withoutAStoredScheduleTradesCloseOnTuesdayAtEightUtc() {
        deadlineWeekLeague(AFTER_THE_LAST_GAME);

        theAnswerSaysTradesAreClosed("the trade deadline passed after week 13");
    }

    @Test
    void aStoredScheduleThatCannotBeReadFallsBackToTuesdayAtEightUtc() {
        deadlineWeekLeague(AFTER_THE_LAST_GAME);
        store.write("nflverse-schedule", "not a schedule");

        theAnswerSaysTradesAreClosed("the trade deadline passed after week 13");
    }

    @Test
    void withoutAStoredScheduleTradesAreOpenUntilTuesdayAtEightUtc() {
        deadlineWeekLeague(Instant.parse("2026-12-08T07:59:00Z"));

        Map<String, Map<String, String>> evidence = askForTrades("{}");

        assertThat(evidence.get("trade-deadline").get("tradeDeadline"))
                .startsWith("The trade deadline is this week.");
        assertThat(evidence).containsKey("offer-1");
    }

    @Test
    void aDeadlineOfWeek99IsNoDeadline() {
        leagueInWeek("sleeper/league-in-season-trade-deadline-99.json",
                "sleeper/rosters-offers.json", "sleeper/state-nfl-week14.json", 14);
        fantasyCalcAnswers("false", "fantasycalc/values-offers-redraft.json");

        Map<String, Map<String, String>> evidence = askForTrades("{}");

        assertThat(evidence).containsKey("offer-1").doesNotContainKey("trade-deadline");
    }

    @Test
    void aLeagueWithTradesTurnedOffGetsNoOffersInAnyWeek() {
        leagueInWeek("sleeper/league-in-season-trades-disabled.json",
                "sleeper/rosters-offers.json", "sleeper/state-nfl-week12.json", 12);
        fantasyCalcAnswers("false", "fantasycalc/values-offers-redraft.json");

        theAnswerSaysTradesAreClosed(
                "trades are turned off in this league for the whole season");
    }

    private void playoffWeeksStored() {
        scheduleStored("nflverse/games-playoff-weeks.csv");
        Map<String, DefenseVersusPosition.TeamDefense> byTeam = new HashMap<>();
        Map.of("CHI", Map.entry("WR", 25), "DET", Map.entry("WR", 30), "GB",
                Map.entry("WR", 22), "DAL", Map.entry("TE", 10), "NYG", Map.entry("TE", 20),
                "WAS", Map.entry("TE", 28)).forEach((team, rank) -> byTeam.put(team,
                new DefenseVersusPosition.TeamDefense(11, Map.of(rank.getKey(),
                        new DefenseVersusPosition.Allowed(10.0 + rank.getValue(),
                                rank.getValue())),
                        new DefenseVersusPosition.Allowed(100.0, 16))));
        new NflverseStore(store).writeDefenseVersusPosition(new DefenseVersusPosition("2026",
                "2026 season to date through week 11", false, 11, WEEK_12, 32, byTeam));
    }

    private static Map<String, String> withoutPlayoffWeeks(Map<String, String> offer) {
        Map<String, String> priced = new HashMap<>(offer);
        priced.remove("playoffWeeks");
        return priced;
    }

    @Test
    void aContenderHearsAboutASoftPlayoffRunAndNoPriceOrVerdictMoves() {
        redraftLeague("sleeper/rosters-offers.json");
        Map<String, Map<String, String>> unpitched = askForTrades("{}");
        assertThat(unpitched.values()).noneSatisfy(facts -> assertThat(facts)
                .containsKey("playoffWeeks"));

        nextQuestion();
        playoffWeeksStored();
        OutboundStubs.llmCallsToolThenPhrases(llm, "suggest_trades", "{}", NARRATION);
        OutboundStubs.llmAdvises(llm, answer(CHECKED_WITH_PLAYOFF_WEEKS, GOEDERT, JEFFERSON,
                BARKLEY));
        ask("what trades should I make and why?");
        Map<String, Map<String, String>> pitched = evidence(RunKind.TRADE_IDEAS);

        assertThat(pitched.get("offer-1")).containsEntry("playoffWeeks", PLAYOFF_WEEKS);
        for (String offer : List.of("offer-1", "offer-2", "offer-3")) {
            assertThat(withoutPlayoffWeeks(pitched.get(offer))).isEqualTo(unpitched.get(offer));
        }
        theUserReads(CHECKED_WITH_PLAYOFF_WEEKS);
    }

    @Test
    void noPlayoffWeeksLineWhenTheLeaguesPlayoffWeeksAreUnknown() {
        leagueInWeek("sleeper/league-in-season-no-playoff-weeks.json",
                "sleeper/rosters-offers.json", "sleeper/state-nfl-week12.json", 12);
        fantasyCalcAnswers("false", "fantasycalc/values-offers-redraft.json");
        playoffWeeksStored();

        Map<String, Map<String, String>> evidence = askForTrades("{}");

        assertThat(evidence).containsKey("offer-1");
        assertThat(evidence.values()).noneSatisfy(facts -> assertThat(facts)
                .containsKey("playoffWeeks"));
    }

    @Test
    void afterTheDeadlineThereAreNoOffersButTheWeaknessesAnswerPointsToWaivers() {
        leagueInWeek("sleeper/league-in-season.json", "sleeper/rosters-offers.json",
                "sleeper/state-nfl-week14.json", 14);
        fantasyCalcAnswers("false", "fantasycalc/values-offers-redraft.json");
        playoffWeeksStored();
        OutboundStubs.llmCallsToolThenPhrases(llm, "suggest_trades", "{}", NARRATION);
        OutboundStubs.llmAdvises(llm, answer(CLOSED));

        ask("what trades should I make?");

        assertThat(records(RunKind.TRADE_IDEAS)).isEmpty();
        Map<String, Map<String, String>> evidence = evidence(RunKind.TEAM_WEAKNESSES);
        assertThat(evidence.get("trade-deadline").get("tradeDeadline"))
                .startsWith("the trade deadline passed after week 13");
        assertThat(evidence.values()).noneSatisfy(facts ->
                assertThat(facts).containsKey("sendTo"));
        assertThat(evidence.values()).noneSatisfy(facts ->
                assertThat(facts).containsKey("playoffWeeks"));
        theUserReads(CLOSED);
    }

    @Test
    void aReplyThatPutsAPlayerInTheWrongOfferIsCorrectedBeforeTheUserSeesIt() {
        redraftLeague("sleeper/rosters-offers.json");
        OutboundStubs.llmCallsToolThenPhrases(llm, "suggest_trades", "{}", NARRATION);
        OutboundStubs.llmAdvises(llm, answer(WRONG_PARTNER, GOEDERT, REAGOR, BARKLEY),
                answer(CHECKED, GOEDERT, JEFFERSON, BARKLEY));

        ask("what trades should I make and why?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Jalen Reagor is in no offer to GridironGoblin")));
        theUserReads(CHECKED);
    }

    @Test
    void aReplyNamingAPlayerFromAnotherRosterIsWithheld() {
        redraftLeague("sleeper/rosters-offers.json");
        OutboundStubs.llmCallsToolThenPhrases(llm, "suggest_trades", "{}", NARRATION);
        OutboundStubs.llmAdvises(llm, answer(NOT_ON_HIS_ROSTER, GOEDERT, JEFFERSON));

        ask("what trades should I make and why?");

        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("names Christian McCaffrey, who is not in the "
                        + "evidence")));
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("Christian McCaffrey")));
        assertThat(records(RunKind.TRADE_IDEAS)).singleElement().satisfies(record ->
                assertThat(record.outcome()).isInstanceOf(Outcome.Withheld.class));
    }

    private void tightEndPointsLeftOnTheBench() {
        List<String> starters = List.of("4046", "4034", "0", JEFFERSON, LAMB, KELCE, "8112",
                "2216", "8135");
        Map<String, Double> points = new LinkedHashMap<>();
        points.put("4046", 22.0);
        points.put("4034", 15.0);
        points.put(JEFFERSON, 16.0);
        points.put(LAMB, 13.0);
        points.put(KELCE, 2.0);
        points.put("8112", 11.0);
        points.put("2216", 10.0);
        points.put("8135", 17.0);
        points.put(REAGOR, 3.0);
        points.put("4029", 3.0);
        points.put(GOEDERT, 14.0);
        for (int week = 9; week <= 11; week++) {
            LineupHistory.played(sleeper, week, starters, points);
        }
    }

    /**
     * WaiverWizard made a waiver claim in week 6, so he is a manager who
     * answers offers. He holds the tight ends a 1-for-2 for CeeDee Lamb
     * can bring back.
     */
    private void waiverWizardMadeAMove() {
        weekSixTransactions = "sleeper/transactions-offers-waiver-move.json";
    }

    @Test
    void tightEndPointsLeftOnTheBenchBringTightEndDepthOffersWithoutSayingWhy() {
        waiverWizardMadeAMove();
        redraftLeague("sleeper/rosters-offers.json");
        Map<String, Map<String, String>> unswayed = askForTrades("{}");
        assertThat(unswayed.values()).noneSatisfy(facts -> assertThat(facts)
                .containsEntry("shape", TIGHT_END_DEPTH));

        nextQuestion();
        tightEndPointsLeftOnTheBench();
        OutboundStubs.llmPhrases(llm, "A quiet week.");
        checkRunner.runCheck();
        nextQuestion();
        OutboundStubs.llmCallsToolThenPhrases(llm, "suggest_trades", "{}", NARRATION);
        OutboundStubs.llmAdvises(llm, answer(TIGHT_END_OFFER, LAMB, TE_DEPTH_03, WR_DEPTH_04));
        ask("what trades should I make and why?");

        Map<String, Map<String, String>> swayed = evidence(RunKind.TRADE_IDEAS);
        assertThat(swayed.values()).anySatisfy(facts -> assertThat(facts)
                .containsEntry("sendTo", "WaiverWizard")
                .containsEntry("youGive", "CeeDee Lamb")
                .containsEntry("youGet", "TE Depth 03 and WR Depth 04")
                .containsEntry("shape", TIGHT_END_DEPTH));
        assertThat(swayed.values()).noneSatisfy(facts -> assertThat(facts.get("youGive"))
                .contains("Dallas Goedert"));
        assertThat(swayed.get("lean")).containsEntry("weighDepthAt", "TE");
        theUserReads(TIGHT_END_OFFER);
    }

    @Test
    void aTradeReplyThatNamesTheUsersHabitIsCorrectedBeforeHeSeesIt() {
        waiverWizardMadeAMove();
        redraftLeague("sleeper/rosters-offers.json");
        tightEndPointsLeftOnTheBench();
        OutboundStubs.llmPhrases(llm, "A quiet week.");
        checkRunner.runCheck();
        nextQuestion();
        OutboundStubs.llmCallsToolThenPhrases(llm, "suggest_trades", "{}", NARRATION);
        OutboundStubs.llmAdvises(llm,
                answer("You keep starting the wrong tight end. " + TIGHT_END_OFFER, LAMB,
                        TE_DEPTH_03, WR_DEPTH_04),
                answer(TIGHT_END_OFFER, LAMB, TE_DEPTH_03, WR_DEPTH_04));

        ask("what trades should I make and why?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("brings up how he manages his team")));
        theUserReads(TIGHT_END_OFFER);
    }

    @Test
    void aCheckNeverSuggestsTradesUnasked() {
        redraftLeague("sleeper/rosters-offers.json");
        OutboundStubs.llmPhrases(llm, "A quiet week.");

        checkRunner.runCheck();

        assertThat(records(RunKind.TRADE_IDEAS)).isEmpty();
    }

}
