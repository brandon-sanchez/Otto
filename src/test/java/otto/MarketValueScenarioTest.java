package otto;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import otto.ask.MarketValueAnswers;
import otto.ask.ToolAnswer;
import otto.check.CheckRunner;
import otto.harness.Fixtures;
import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.StoredRecords;
import otto.harness.WireSeamTest;
import otto.market.MarketDay;
import otto.market.MarketFormat;
import otto.market.MarketPlayer;
import otto.market.MarketValueRefresh;
import otto.market.MarketValues;
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
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Market values: what a player or pick is worth on the trade market in
 * the user's league, read from FantasyCalc once a day for the league's
 * format, with DynastyProcess behind it for dynasty players and picks.
 *
 * <p>The values are a trimmed recording of FantasyCalc's real lists for
 * a 12-team Superflex PPR league, with names matched to the test player
 * directory. In the redraft list Puka Nacua is worth 3972, 35th on the
 * list and 12th among WRs, in tier 17, down 5493 over 30 days.
 */
class MarketValueScenarioTest extends WireSeamTest {

    private static final String VALUES_PATH = "/values/current";
    private static final String DYNASTY_PROCESS_PATH =
            "/dynastyprocess/data/master/files/values.csv";

    private static final MarketFormat REDRAFT_SUPERFLEX =
            new MarketFormat(false, 2, 12, BigDecimal.ONE);

    private static final String NACUA_MESSAGE = "Puka Nacua is worth 3972 on FantasyCalc in "
            + "your 12-team Superflex PPR redraft league, 35th on the list and 12th among WRs, "
            + "in tier 17. His value is down 58% over the last 30 days.";

    private static final String NUMBERLESS_ANSWER = answer("Here is what the market says.");

    @Autowired
    private MarketValueAnswers marketValues;

    @Autowired
    private TelegramWebhook webhook;

    @Autowired
    private MarketValues values;

    @Autowired
    private MarketValueRefresh refresh;

    @Autowired
    private DecisionRecords records;

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private CheckRunner checkRunner;

    @Value("${otto.storage-dir}")
    private String storageDir;

    private static String answer(String message, String... players) {
        return """
                {
                  "message": %s,
                  "move": null,
                  "players": [%s],
                  "claims": [],
                  "comparisons": []
                }
                """.formatted(OutboundStubs.jsonString(message),
                String.join(", ", List.of(players).stream().map(id -> "\"" + id + "\"")
                        .toList()));
    }

    private void redraftSuperflexLeague() {
        SleeperStubs.healthyInSeason(sleeper);
        OutboundStubs.telegramOk(telegram);
    }

    private void league(String fixture) {
        redraftSuperflexLeague();
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH, fixture, "league-market");
    }

    private static MappingBuilder valuesFor(boolean dynasty, int quarterbacks, int teams,
            String ppr) {
        return get(urlPathEqualTo(VALUES_PATH))
                .withQueryParam("isDynasty", equalTo(String.valueOf(dynasty)))
                .withQueryParam("numQbs", equalTo(String.valueOf(quarterbacks)))
                .withQueryParam("numTeams", equalTo(String.valueOf(teams)))
                .withQueryParam("ppr", equalTo(ppr));
    }

    private static void fantasyCalcAnswers(MappingBuilder request, String fixture) {
        fantasyCalc.stubFor(request.willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(Fixtures.read(fixture))));
    }

    private static void fantasyCalcIsDown() {
        fantasyCalc.stubFor(get(urlPathEqualTo(VALUES_PATH))
                .willReturn(aResponse().withStatus(503)));
    }

    private void dynastyProcessAndIds() {
        NflverseStubs.healthy(nflverse);
        feeds.updateIfDue();
        nflverse.stubFor(get(urlEqualTo(DYNASTY_PROCESS_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/csv")
                .withBody(Fixtures.read("dynastyprocess/values.csv"))));
    }

    private int fantasyCalcRequests() {
        return fantasyCalc.findAll(getRequestedFor(urlPathEqualTo(VALUES_PATH))).size();
    }

    private List<DecisionRecord> marketRecords() {
        return StoredRecords.of(records, storageDir, "777001", "1389748403805650944",
                RunKind.MARKET_VALUE);
    }

    private Map<String, Map<String, String>> lastEvidence() {
        DecisionRecord record = marketRecords().getLast();
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

    @Test
    void askingWhatAPlayerIsWorthSendsHisCheckedValueRankTierAndTrend() {
        redraftSuperflexLeague();
        fantasyCalcAnswers(valuesFor(false, 2, 12, "1"), "fantasycalc/values-redraft.json");
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_market_value",
                "{\"subject\":\"Puka Nacua\"}", NACUA_MESSAGE);
        OutboundStubs.llmAdvisesOn(llm, "market-value-9493", answer(NACUA_MESSAGE, "9493"));

        assertThat(webhook.handle(WEBHOOK_SECRET,
                OutboundStubs.textMessage("What's Puka Nacua worth in my league?")))
                .isEqualTo(WebhookResult.OK);

        Map<String, Map<String, String>> evidence = lastEvidence();
        assertThat(evidence.get("market-value-9493"))
                .containsEntry("value", "3972")
                .containsEntry("league", "12-team Superflex PPR redraft")
                .containsEntry("source", "FantasyCalc")
                .containsEntry("listAge", "today");
        assertThat(evidence.get("market-overall-rank-9493")).containsEntry("rank", "35th");
        assertThat(evidence.get("market-position-rank-9493")).containsEntry("rank", "12th");
        assertThat(evidence.get("market-tier-9493")).containsEntry("tier", "17");
        assertThat(evidence.get("market-trend-9493"))
                .containsEntry("direction", "down")
                .containsEntry("change", "5493")
                .containsEntry("percent", "58%");
        assertThat(evidence.get("market-profile-9493"))
                .containsEntry("age", "25.5")
                .containsEntry("nflSeasonsPlayed", "3")
                .containsEntry("rosteredIn", "100% of leagues")
                .containsEntry("tradeShare", "4.1% of the trades FantasyCalc logged include him");
        telegram.verify(postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(NACUA_MESSAGE))));
        assertThat(marketRecords()).singleElement().satisfies(record ->
                assertThat(record.outcome()).isEqualTo(new Outcome.Sent(NACUA_MESSAGE)));
    }

    @Test
    void fantasyCalcIsAskedOnceADayForEachFormat() {
        redraftSuperflexLeague();
        fantasyCalcAnswers(valuesFor(false, 2, 12, "1"), "fantasycalc/values-redraft.json");
        OutboundStubs.llmAdvises(llm, NUMBERLESS_ANSWER);

        marketValues.answer("Puka Nacua");
        marketValues.answer("Bijan Robinson");
        refresh.refresh();
        clock.advance(Duration.ofHours(5));
        marketValues.answer("Puka Nacua");

        assertThat(fantasyCalcRequests()).isEqualTo(1);

        clock.advance(Duration.ofDays(1));
        marketValues.answer("Puka Nacua");

        assertThat(fantasyCalcRequests()).isEqualTo(2);
    }

    @Test
    void everyFantasyCalcFieldIsKeptForEachDay() {
        redraftSuperflexLeague();
        fantasyCalcAnswers(valuesFor(false, 2, 12, "1"), "fantasycalc/values-redraft.json");

        refresh.refresh();

        LocalDate today = TEST_START.atZone(ZoneOffset.UTC).toLocalDate();
        MarketPlayer nacua = values.onDay(REDRAFT_SUPERFLEX, today)
                .flatMap(day -> day.entry("9493"))
                .orElseThrow();
        assertThat(nacua).isEqualTo(new MarketPlayer("9493", "Puka Nacua", "WR",
                Optional.of("LAR"), 3972, Optional.of(3972), Optional.of(35), Optional.of(12),
                Optional.of(17), Optional.of(-5493), Optional.empty(), Optional.of(0.9998),
                Optional.of(0.0408), Optional.of(25.5), Optional.of(3)));
    }

    @Test
    void eachDaysValuesAreKeptSoChangesOverAnyWindowCanBeMeasured() {
        redraftSuperflexLeague();
        fantasyCalcAnswers(valuesFor(false, 2, 12, "1"), "fantasycalc/values-redraft.json");
        refresh.refresh();
        clock.advance(Duration.ofDays(3));
        fantasyCalcAnswers(valuesFor(false, 2, 12, "1"),
                "fantasycalc/values-redraft-next-day.json");
        OutboundStubs.llmAdvises(llm, NUMBERLESS_ANSWER);

        marketValues.answer("Puka Nacua");

        MarketDay latest = values.onDay(REDRAFT_SUPERFLEX,
                TEST_START.plus(Duration.ofDays(3)).atZone(ZoneOffset.UTC).toLocalDate())
                .orElseThrow();
        assertThat(values.change(REDRAFT_SUPERFLEX, latest, "9493", 30))
                .contains(new MarketValues.Change(3, 3972, 3575));
        assertThat(values.change(REDRAFT_SUPERFLEX, latest, "9493", 3))
                .contains(new MarketValues.Change(3, 3972, 3575));
        assertThat(values.change(REDRAFT_SUPERFLEX, latest, "9493", 2)).isEmpty();
        assertThat(lastEvidence().get("market-history-9493"))
                .containsEntry("from", "3972")
                .containsEntry("to", "3575")
                .containsEntry("direction", "down")
                .containsEntry("percent", "10%")
                .containsEntry("window", "over the last 3 days of Otto's own daily record");
        assertThat(lastEvidence().get("market-trend-9493")).containsEntry("change", "5890");
    }

    @Test
    void aDayFantasyCalcFailsKeepsTheLastGoodListAndSaysHowOldItIs() {
        redraftSuperflexLeague();
        fantasyCalcAnswers(valuesFor(false, 2, 12, "1"), "fantasycalc/values-redraft.json");
        refresh.refresh();
        clock.advance(Duration.ofDays(2));
        fantasyCalc.resetAll();
        fantasyCalcIsDown();
        OutboundStubs.llmAdvises(llm, NUMBERLESS_ANSWER);

        ToolAnswer<MarketValueAnswers.Reply> answer = marketValues.answer("Puka Nacua");
        marketValues.answer("Puka Nacua");

        assertThat(answer.facts().source()).isEqualTo("FantasyCalc");
        assertThat(lastEvidence().get("market-value-9493"))
                .containsEntry("value", "3972")
                .containsEntry("listAge", "2 days ago")
                .containsEntry("staleness",
                        "FantasyCalc could not be reached today, so this value is from its "
                                + "list of 2 days ago.");
        assertThat(fantasyCalcRequests()).isEqualTo(1);
    }

    @Test
    void anOutageWithNoListStoredSaysPlainlyThatThereIsNoValue() {
        redraftSuperflexLeague();
        fantasyCalcIsDown();

        ToolAnswer<MarketValueAnswers.Reply> answer = marketValues.answer("Puka Nacua");

        assertThat(answer.unavailable()).isEqualTo("FantasyCalc has not answered today and I "
                + "have no earlier list of its values for a 12-team Superflex PPR redraft "
                + "league");
        assertThat(marketRecords()).isEmpty();
    }

    @Test
    void aOneQuarterbackHalfPprLeagueAsksForItsOwnFormat() {
        league("sleeper/league-market-1qb-half-ppr.json");
        fantasyCalcAnswers(valuesFor(false, 1, 10, "0.5"), "fantasycalc/values-redraft.json");
        OutboundStubs.llmAdvises(llm, NUMBERLESS_ANSWER);

        marketValues.answer("Puka Nacua");

        assertThat(fantasyCalcRequests()).isEqualTo(1);
        assertThat(lastEvidence().get("market-value-9493"))
                .containsEntry("league", "10-team 1QB half-PPR redraft");
    }

    @Test
    void aDynastyLeagueReadsDynastyValuesAndFallsBackToDynastyProcess() {
        league("sleeper/league-dynasty.json");
        dynastyProcessAndIds();
        fantasyCalcAnswers(valuesFor(true, 2, 12, "1"), "fantasycalc/values-dynasty.json");
        OutboundStubs.llmAdvises(llm, NUMBERLESS_ANSWER);

        assertThat(marketValues.answer("Puka Nacua").facts().source()).isEqualTo("FantasyCalc");
        assertThat(lastEvidence().get("market-value-9493"))
                .containsEntry("value", "7146")
                .containsEntry("league", "12-team Superflex PPR dynasty");

        clock.advance(Duration.ofMinutes(1));
        assertThat(marketValues.answer("Garrett Wilson").facts().source())
                .isEqualTo("DynastyProcess");
        assertThat(lastEvidence().get("market-value-8112"))
                .containsEntry("value", "4602")
                .containsEntry("source", "DynastyProcess")
                .containsKey("fallback");
    }

    @Test
    void draftPicksArePricedInDynastyFromEitherSource() {
        league("sleeper/league-dynasty.json");
        dynastyProcessAndIds();
        fantasyCalcAnswers(valuesFor(true, 2, 12, "1"), "fantasycalc/values-dynasty.json");
        OutboundStubs.llmAdvises(llm, NUMBERLESS_ANSWER);

        marketValues.answer("an early 2027 first");
        assertThat(lastEvidence().get("market-value-pick"))
                .containsEntry("subject", "2027 early 1st-round pick")
                .containsEntry("value", "4982")
                .containsEntry("source", "FantasyCalc")
                .containsEntry("nflDraft", "1st round");

        clock.advance(Duration.ofMinutes(1));
        marketValues.answer("2027 5th");
        assertThat(lastEvidence().get("market-value-pick"))
                .containsEntry("subject", "2027 5th-round pick")
                .containsEntry("value", "7")
                .containsEntry("source", "DynastyProcess");
    }

    @Test
    void aDynastyLeagueWithFantasyCalcDownUsesDynastyProcess() {
        league("sleeper/league-dynasty.json");
        dynastyProcessAndIds();
        fantasyCalcIsDown();
        OutboundStubs.llmAdvises(llm, NUMBERLESS_ANSWER);

        assertThat(marketValues.answer("Puka Nacua").facts().source())
                .isEqualTo("DynastyProcess");
        assertThat(lastEvidence().get("market-value-9493")).containsEntry("value", "7700");
    }

    @Test
    void aRedraftLeagueDoesNotPriceDraftPicks() {
        redraftSuperflexLeague();
        fantasyCalcAnswers(valuesFor(false, 2, 12, "1"), "fantasycalc/values-redraft.json");

        assertThat(marketValues.answer("2027 1st").unavailable())
                .contains("redraft league");
    }

    @Test
    void aLikelyKeeperAddsHisDynastyValueAboveTheKeeperLineAndNobodyElseDoes() {
        league("sleeper/league-keeper.json");
        OutboundStubs.llmPhrases(llm, "A quiet week.");
        checkRunner.runCheck();
        llm.resetAll();
        dynastyProcessAndIds();
        fantasyCalcAnswers(valuesFor(false, 2, 12, "1"), "fantasycalc/values-redraft.json");
        fantasyCalcAnswers(valuesFor(true, 2, 12, "1"), "fantasycalc/values-dynasty.json");
        OutboundStubs.llmAdvises(llm, NUMBERLESS_ANSWER);

        assertThat(marketValues.answer("Bijan Robinson").unavailable()).isNull();
        assertThat(lastEvidence().get("market-value-9509"))
                .containsEntry("value", "13990")
                .containsEntry("league", "12-team Superflex PPR keeper");
        assertThat(lastEvidence().get("market-keeper-9509"))
                .containsEntry("likelyKeeper", "yes")
                .containsEntry("redraftValue", "8945")
                .containsEntry("dynastyValue", "9636")
                .containsEntry("keeperLine", "4591")
                .containsEntry("keeperBonus", "5045");

        clock.advance(Duration.ofMinutes(1));
        marketValues.answer("Patrick Mahomes");
        assertThat(lastEvidence().get("market-value-4046")).containsEntry("value", "4184");
        assertThat(lastEvidence().get("market-keeper-4046"))
                .containsEntry("likelyKeeper", "no")
                .doesNotContainKey("keeperBonus");
    }

    @Test
    void aWrongNumberIsCorrectedOnceBeforeTheAnswerIsSent() {
        redraftSuperflexLeague();
        fantasyCalcAnswers(valuesFor(false, 2, 12, "1"), "fantasycalc/values-redraft.json");
        String wrong = NACUA_MESSAGE.replace("3972", "4100");
        OutboundStubs.llmAdvises(llm, answer(wrong, "9493"), answer(NACUA_MESSAGE, "9493"));

        ToolAnswer<MarketValueAnswers.Reply> answer = marketValues.answer("Puka Nacua");

        assertThat(answer.facts().message()).isEqualTo(NACUA_MESSAGE);
        assertThat(marketRecords().getLast().exchanges()).hasSize(2);
    }

    @Test
    void anAnswerThatFailsTheCheckTwiceIsWithheld() {
        redraftSuperflexLeague();
        fantasyCalcAnswers(valuesFor(false, 2, 12, "1"), "fantasycalc/values-redraft.json");
        OutboundStubs.llmAdvises(llm, answer(NACUA_MESSAGE.replace("35th", "3rd"), "9493"));

        ToolAnswer<MarketValueAnswers.Reply> answer = marketValues.answer("Puka Nacua");

        assertThat(answer.facts()).isNull();
        assertThat(answer.unavailable()).contains("failed its fact check twice");
        assertThat(marketRecords().getLast().outcome())
                .isInstanceOf(Outcome.WithheldWithNotice.class);
    }
}
