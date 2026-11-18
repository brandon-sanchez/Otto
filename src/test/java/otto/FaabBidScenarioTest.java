package otto;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.harness.Fixtures;
import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.nflverse.DefenseVersusPositionBuilder;
import otto.nflverse.NflverseFeedService;
import otto.records.DecisionRecord;
import otto.records.DecisionRecords;
import otto.records.Outcome;
import otto.records.RunKind;
import otto.research.ResearchDiscovery;
import otto.settings.SettingsService;
import otto.storage.OttoJson;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The evidence-based board's FAAB bids, on the waiver week fixture: a
 * $100 FAAB league with a $0 minimum bid, the user with nothing spent
 * and five open roster spots, and the board due at 18:00 Los Angeles
 * time the evening before Wednesday's claims.
 */
class FaabBidScenarioTest extends WireSeamTest {

    private static final String USER_ID = "777001";
    private static final String LEAGUE_ID = "1389748403805650944";

    private static final Instant TUESDAY_EVENING = ZonedDateTime.of(2026, 9, 15, 18, 0, 0, 0,
            ZoneId.of("America/Los_Angeles")).toInstant();

    private static final String IRVING_REASONING = "Bucky Irving played 71% of offensive snaps "
            + "in his latest game, and his coach named him the starter.";
    private static final String ROBINSON_REASONING = "Wandale Robinson drew 13 targets, a target "
            + "share of 22%, on 78% of offensive snaps.";
    private static final String LIMIT = "Past that you are paying for more than a lead role.";
    private static final String OPENING = "A running back stands out this week.";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private DefenseVersusPositionBuilder defenseBuilder;

    @Autowired
    private ResearchDiscovery researchDiscovery;

    @Autowired
    private DecisionRecords decisionRecords;

    @Autowired
    private SettingsService settings;

    @Autowired
    private OttoProperties properties;

    private static String bid(int low, int target, int high) {
        return "{\"low\": %d, \"target\": %d, \"high\": %d}".formatted(low, target, high);
    }

    private static String pick(String playerId, String reasoning, String bid, String drop) {
        return """
                {"playerId": "%s", "reasoning": "%s", "bid": %s, "limit": "%s", "drop": %s}"""
                .formatted(playerId, reasoning, bid, bid.equals("null") ? "" : LIMIT,
                        drop == null ? "null" : "\"" + drop + "\"");
    }

    private static String irving(String bid) {
        return pick("10001", IRVING_REASONING, bid, null);
    }

    private static String robinson(String bid) {
        return pick("10004", ROBINSON_REASONING, bid, null);
    }

    private static String board(List<String> picks, List<String> together) {
        return """
                {
                  "hold": false,
                  "opening": "%s",
                  "picks": [%s],
                  "optional": [],
                  "together": [%s],
                  "claims": [
                    {"text": "Bucky Irving played 71%% of offensive snaps", "evidence": ["usage-10001"]}
                  ]
                }
                """.formatted(OPENING, String.join(",", picks), together.stream()
                .map(id -> "\"" + id + "\"").reduce((a, b) -> a + "," + b).orElse(""));
    }

    private static String board(String... picks) {
        return board(List.of(picks), List.of());
    }

    private void tuesdayEvening(Runnable leagueVariant, String... answers) {
        SleeperStubs.waiverWeek(sleeper);
        leagueVariant.run();
        NflverseStubs.waiverWeekReadyForTheBoard(nflverse);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "A lineup alert.");
        checkRunner.runCheck();
        feeds.updateIfDue();
        defenseBuilder.build();
        researchDiscovery.discover();
        llm.resetAll();
        telegram.resetRequests();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Five waiver targets for Wednesday.");
        OutboundStubs.llmAdvisesInOrderOn(llm, OutboundStubs.WAIVER_BOARD_REQUEST, answers);
        clock.set(TUESDAY_EVENING);
        checkRunner.runCheck();
    }

    private void tuesdayEvening(String... answers) {
        tuesdayEvening(() -> { }, answers);
    }

    @Test
    void aVerifiedBudgetPutsOttosTargetAndRangeOnEachPick() {
        tuesdayEvening(board(irving(bid(7, 9, 12))));

        DecisionRecord record = onlyBoardRecord();
        assertThat(record.exchanges()).singleElement()
                .satisfies(exchange -> assertThat(exchange.checkFailures()).isEmpty());
        assertThat(record.roster().faabRemaining()).contains(100);
        assertThat(onlyMessage(record)).contains("1. <b>Bucky Irving</b> · RB, TB\n"
                + "💰 Bid <b>$9</b> (range $7–12)\n<blockquote expandable>" + IRVING_REASONING + "\n" + LIMIT);
        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(OutboundStubs.WAIVER_BOARD_REQUEST))
                .withRequestBody(containing("budgetLeft"))
                .withRequestBody(containing("minimumBid"))
                .withRequestBody(containing("highestLegalBid")));
    }

    @Test
    void aBidOutsideTheBoundsIsCorrectedOnce() {
        tuesdayEvening(board(irving(bid(10, 9, 120))), board(irving(bid(7, 9, 12))));

        DecisionRecord record = onlyBoardRecord();
        assertThat(record.exchanges()).hasSize(2);
        assertThat(record.exchanges().getFirst().checkFailures())
                .anySatisfy(failure -> assertThat(failure).contains("low <= target <= high"))
                .anySatisfy(failure -> assertThat(failure)
                        .contains("high of $120 is more than the $100 the user has left"));
        assertThat(record.outcome()).isInstanceOf(Outcome.SentBoard.class);
    }

    @Test
    void aBidInFractionsOfADollarIsSentBack() {
        tuesdayEvening(board(irving("{\"low\": 7.5, \"target\": 9, \"high\": 12}")),
                board(irving(bid(7, 9, 12))));

        assertThat(onlyBoardRecord().exchanges().getFirst().checkFailures())
                .anySatisfy(failure -> assertThat(failure).contains("whole dollars"));
    }

    @Test
    void aPickWithoutABidIsSentBackWhenTheBudgetIsVerified() {
        tuesdayEvening(board(irving("null")), board(irving(bid(7, 9, 12))));

        assertThat(onlyBoardRecord().exchanges().getFirst().checkFailures())
                .anySatisfy(failure -> assertThat(failure).contains("Give Bucky Irving a \"bid\""));
    }

    @Test
    void theUsersOwnLimitCapsEveryBid() {
        settings.apply("set", "max_bid", "10", Instant.now());

        tuesdayEvening(board(irving(bid(7, 9, 12))), board(irving(bid(7, 9, 10))));

        DecisionRecord record = onlyBoardRecord();
        assertThat(record.exchanges().getFirst().checkFailures())
                .anySatisfy(failure -> assertThat(failure)
                        .contains("high of $12 is above the user's own limit of $10"));
        assertThat(onlyMessage(record)).contains("💰 Bid <b>$9</b> (range $7–10)");
        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("$10 per player, set by the user")));
    }

    @Test
    void anUnknownBudgetWithholdsDollarBidsAndNeverFallsBackToBands() {
        tuesdayEvening(() -> leagueWith(league -> ((ObjectNode) league.path("settings"))
                        .remove("waiver_budget")),
                board(irving(bid(7, 9, 12))), board(irving("null")));

        DecisionRecord record = onlyBoardRecord();
        assertThat(record.exchanges().getFirst().checkFailures())
                .anySatisfy(failure -> assertThat(failure).contains(
                        "Bids are withheld because I couldn't read the league's FAAB budget"));
        assertThat(record.roster().faabRemaining()).isEmpty();
        assertThat(onlyMessage(record))
                .contains("I'm leaving out dollar bids this round because I couldn't read the "
                        + "league's FAAB budget.")
                .doesNotContain("$");
    }

    @Test
    void aZeroBudgetIsAVerifiedBudgetThatStillAllowsALegalZeroBid() {
        tuesdayEvening(() -> rostersWith(rosters -> ((ObjectNode) rosters.get(0)
                        .path("settings")).put("waiver_budget_used", 100)),
                board(irving(bid(0, 0, 0))));

        DecisionRecord record = onlyBoardRecord();
        assertThat(record.roster().faabRemaining()).contains(0);
        assertThat(onlyMessage(record)).contains("💰 Bid <b>$0</b> (range $0–0)")
                .doesNotContain("dollar bids");
    }

    @Test
    void aPriorityWaiverLeagueCarriesNoBid() {
        tuesdayEvening(() -> leagueWith(league -> ((ObjectNode) league.path("settings"))
                        .put("waiver_type", 0)),
                board(irving(bid(7, 9, 12))), board(irving("null")));

        DecisionRecord record = onlyBoardRecord();
        assertThat(record.exchanges().getFirst().checkFailures())
                .anySatisfy(failure -> assertThat(failure).contains("claims by waiver priority"));
        assertThat(onlyMessage(record)).doesNotContain("$").doesNotContain("dollar bids");
    }

    @Test
    void theLeaguesOwnBidHistoryReachesTheModelLostClaimsIncluded() {
        tuesdayEvening(() -> {
            SleeperStubs.stubJson(sleeper, SleeperStubs.TRANSACTIONS_WEEK_1_PATH,
                    "sleeper/transactions-faab-week1.json", "transactions-faab-w1");
            SleeperStubs.stubJson(sleeper, SleeperStubs.TRANSACTIONS_PATH,
                    "sleeper/transactions-none.json", "transactions-v1");
        }, board(irving(bid(7, 9, 12))));

        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(OutboundStubs.WAIVER_BOARD_REQUEST))
                .withRequestBody(containing("week 1: Saquon Barkley (RB) went to GridironGoblin "
                        + "for $23, lost bids $18 (the user)"))
                .withRequestBody(containing("2 FAAB claims this season, 1 won and 1 lost"))
                .withRequestBody(containing("GridironGoblin $60")));
    }

    @Test
    void publishedConsensusIsAnAverageOnlyOverThreeIndependentSources() {
        tuesdayEvening(() -> {
            SleeperStubs.stubNews(sleeper, "10004", "sleeper/news-10004-faab.json");
            SleeperStubs.stubNews(sleeper, "10001", "sleeper/news-10001-faab.json");
        }, board(irving(bid(7, 9, 12)), robinson(bid(6, 8, 11))));

        String message = onlyMessage(onlyBoardRecord());
        assertThat(message)
                .contains("2. <b>Wandale Robinson</b> · WR, NYG\n"
                        + "Published FAAB advice comes to about 10% of the budget ($10 in your league).\n"
                        + "💰 Bid <b>$8</b> (range $6–11)")
                .contains("1. <b>Bucky Irving</b> · RB, TB\n"
                        + "I found only 2 published FAAB suggestions, which is too few to call a consensus.");
        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("the average of 3 independent sources"))
                .withRequestBody(containing("$20, its budget not stated, so not converted")));
    }

    @Test
    void claimsPlacedTogetherStayAffordableTogether() {
        tuesdayEvening(
                board(List.of(irving(bid(40, 50, 60)), robinson(bid(30, 40, 50))),
                        List.of("10001", "10004")),
                board(List.of(irving(bid(30, 35, 40)), robinson(bid(20, 25, 30))),
                        List.of("10001", "10004")));

        DecisionRecord record = onlyBoardRecord();
        assertThat(record.exchanges().getFirst().checkFailures())
                .anySatisfy(failure -> assertThat(failure)
                        .contains("their highs add up to $110, more than the $100"));
        assertThat(onlyMessage(record)).contains("Put in claims for Bucky Irving and Wandale "
                + "Robinson together. At the top of every range that's $70, which fits in the "
                + "$100 you have left.");
    }

    @Test
    void aFullRosterNeedsADifferentLegalDropForEveryClaimPlacedTogether() {
        tuesdayEvening(() -> leagueWith(league -> {
                    ArrayNode slots = (ArrayNode) league.path("roster_positions");
                    for (int bench = 0; bench < 5; bench++) {
                        slots.remove(slots.size() - 1);
                    }
                }),
                board(List.of(pick("10001", IRVING_REASONING, bid(7, 9, 12), null),
                        pick("10004", ROBINSON_REASONING, bid(6, 8, 11), "4866")),
                        List.of()),
                board(List.of(pick("10001", IRVING_REASONING, bid(7, 9, 12), "5850"),
                        pick("10004", ROBINSON_REASONING, bid(6, 8, 11), "5850")),
                        List.of("10001", "10004")));

        DecisionRecord record = onlyBoardRecord();
        assertThat(record.exchanges()).hasSize(2);
        assertThat(record.exchanges().getFirst().checkFailures())
                .anySatisfy(failure -> assertThat(failure).contains("roster is full"))
                .anySatisfy(failure -> assertThat(failure)
                        .contains("4866 is not on the user's active roster"));
        assertThat(record.exchanges().getLast().checkFailures())
                .anySatisfy(failure -> assertThat(failure)
                        .contains("5850 is dropped for two claims placed together"));
        assertThat(record.outcome()).isInstanceOf(Outcome.WithheldWithNotice.class);
    }

    @Test
    void aLegalDropIsNamedOnTheBoard() {
        tuesdayEvening(() -> leagueWith(league -> {
                    ArrayNode slots = (ArrayNode) league.path("roster_positions");
                    for (int bench = 0; bench < 5; bench++) {
                        slots.remove(slots.size() - 1);
                    }
                }),
                board(List.of(pick("10001", IRVING_REASONING, bid(7, 9, 12), "5850"),
                        pick("10004", ROBINSON_REASONING, bid(6, 8, 11), "6813")),
                        List.of("10001", "10004")));

        assertThat(onlyMessage(onlyBoardRecord()))
                .contains("➖ Drop: Josh Jacobs\n<blockquote expandable>")
                .contains("➖ Drop: Dallas Goedert");
    }

    private void leagueWith(Consumer<JsonNode> change) {
        stubChanged(SleeperStubs.LEAGUE_PATH, "sleeper/league-in-season.json", change);
    }

    private void rostersWith(Consumer<JsonNode> change) {
        stubChanged(SleeperStubs.ROSTERS_PATH, "sleeper/rosters-waivers.json", change);
    }

    private static void stubChanged(String path, String fixture, Consumer<JsonNode> change) {
        JsonNode body;
        try {
            body = OttoJson.MAPPER.readTree(Fixtures.read(fixture));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        change.accept(body);
        sleeper.stubFor(get(urlEqualTo(path)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withHeader("ETag", "\"changed-" + path.hashCode() + "\"")
                .withBody(body.toString())));
    }

    private static String onlyMessage(DecisionRecord record) {
        assertThat(record.outcome()).isInstanceOf(Outcome.SentBoard.class);
        return String.join("\n\n", ((Outcome.SentBoard) record.outcome()).messages());
    }

    private DecisionRecord onlyBoardRecord() {
        Path league = Path.of(properties.storageDir(), "decision-records", "records",
                "user=" + USER_ID, "league=" + LEAGUE_ID);
        try (Stream<Path> files = Files.list(league)) {
            List<DecisionRecord> records = files
                    .map(file -> file.getFileName().toString())
                    .map(name -> name.substring("run=".length(), name.length() - ".json".length()))
                    .map(runId -> decisionRecords.read(USER_ID, LEAGUE_ID, runId))
                    .flatMap(Optional::stream)
                    .filter(record -> record.kind() == RunKind.WAIVER_BOARD)
                    .toList();
            assertThat(records).hasSize(1);
            return records.getFirst();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
