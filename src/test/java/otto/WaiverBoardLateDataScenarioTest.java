package otto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.nflverse.DefenseVersusPositionBuilder;
import otto.nflverse.NflverseFeedService;
import otto.records.DecisionRecord;
import otto.records.DecisionRecords;
import otto.records.Evidence;
import otto.records.Outcome;
import otto.records.RunKind;
import otto.research.ResearchDiscovery;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The waiver board when the latest completed week is late: 2026 week 2
 * on the waiver week fixture, whose claims close at 03:00 New York time
 * on Wednesday 16 September, midnight in Los Angeles. The board is due
 * at 18:00 Los Angeles time the evening before, and the fallback buffer
 * opens three hours before the deadline, at 21:00.
 */
class WaiverBoardLateDataScenarioTest extends WireSeamTest {

    private static final String USER_ID = "777001";
    private static final String LEAGUE_ID = "1389748403805650944";

    private static final ZoneId LOS_ANGELES = ZoneId.of("America/Los_Angeles");
    private static final Instant BOARD_DUE = at(15, 18, 0);
    private static final Instant FALLBACK = at(15, 21, 0);
    private static final Instant DEADLINE = at(16, 0, 0);

    private static final String BOARD_REQUEST = OutboundStubs.WAIVER_BOARD_REQUEST;

    private static final String OPENING = "Bucky Irving is the claim to make this week.";

    private static final String BOARD = """
            {
              "hold": false,
              "opening": "%s",
              "picks": [{"playerId": "10001", "reasoning": "Bucky Irving played 71%% of \
            offensive snaps in his latest game, and his coach named him the starter.", \
            "bid": {"low": 1, "target": 2, "high": 3}, "limit": "He is a depth add."}],
              "optional": [],
              "claims": [
                {"text": "Bucky Irving played 71%% of offensive snaps", "evidence": ["usage-10001"]}
              ]
            }
            """.formatted(OPENING);

    private static final String TRENDING_ONLY =
            "trending, but this week's usage numbers aren't in yet";

    private static final String MISSING_GAME = "the snap counts lack week 1's BUF at MIA game";

    private static final String SLEEPER_WEEK_ONE = "sleeper/stats-2026-1-waivers.json";

    private static final String FILLED_GAME = "the snap counts for week 1's BUF at MIA game "
            + "come from Sleeper's stats, because nflverse had not published them when the "
            + "board was built";

    private static final String RAY_DAVIS_FROM_SLEEPER = "68% of offensive snaps (41 snaps)";

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
    private OttoProperties properties;

    private static Instant at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, LOS_ANGELES).toInstant();
    }

    private void aWaiverWeekWithTheBuffaloGameMissing() {
        aWaiverWeekOnDisk(() -> NflverseStubs.waiverWeekMissingAGame(nflverse));
    }

    private void aWaiverWeekOnDisk(Runnable nflverseStubs) {
        SleeperStubs.waiverWeek(sleeper);
        nflverseStubs.run();
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
        OutboundStubs.llmAdvisesInOrderOn(llm, BOARD_REQUEST, BOARD);
    }

    private void checkAt(Instant now) {
        clock.set(now);
        checkRunner.runCheck();
    }

    @Test
    void aMissingWeekHoldsTheBoardUntilTheWeekArrives() {
        aWaiverWeekWithTheBuffaloGameMissing();

        checkAt(BOARD_DUE);
        checkAt(BOARD_DUE.plus(Duration.ofHours(1)));
        assertThat(boardRecords()).isEmpty();
        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(BOARD_REQUEST)));

        NflverseStubs.lateSnapCountsArrive(nflverse);
        clock.set(BOARD_DUE.plus(Duration.ofHours(2)));
        feeds.updateIfDue();
        checkRunner.runCheck();

        assertThat(boardMessages()).singleElement().satisfies(message -> assertThat(message)
                .startsWith("Your waiver options\n\n" + OPENING)
                .doesNotContain("not all in"));
    }

    @Test
    void aScheduleThatHasNotCaughtUpWithSleeperStillHoldsTheBoard() {
        aWaiverWeekOnDisk(() -> NflverseStubs.waiverWeekWithAnUnscoredSchedule(nflverse));

        checkAt(BOARD_DUE);
        assertThat(boardRecords()).isEmpty();

        checkAt(FALLBACK);
        assertThat(boardRecords()).hasSize(1);
        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(BOARD_REQUEST))
                .withRequestBody(containing("the weekly stats cannot be checked for week 1"))
                .withRequestBody(containing("the snap counts cannot be checked for week 1")));
    }

    @Test
    void theFallbackBoardGoesThreeHoursBeforeTheDeadlineAndSaysWhatIsMissing() {
        aWaiverWeekWithTheBuffaloGameMissing();

        checkAt(BOARD_DUE);
        checkAt(FALLBACK.minus(Duration.ofMinutes(1)));
        assertThat(boardRecords()).isEmpty();
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));

        checkAt(FALLBACK);
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", containing(MISSING_GAME))));

        assertThat(boardMessages()).singleElement().satisfies(message -> assertThat(message)
                .startsWith("Your waiver options\n\nWeek 1's numbers aren't all in yet, "
                        + "because " + MISSING_GAME + ".")
                .contains("So this board uses the week 1 numbers that have arrived, "
                        + "this week's projections and the latest news")
                .contains(OPENING));
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(BOARD_REQUEST))
                .withRequestBody(containing(MISSING_GAME)));
    }

    @Test
    void aFirstCheckInsideTheBufferStillBuildsTheFallbackOnceBeforeTheDeadline() {
        aWaiverWeekWithTheBuffaloGameMissing();

        checkAt(DEADLINE.minus(Duration.ofMinutes(30)));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", containing(MISSING_GAME))));
        checkAt(DEADLINE.minus(Duration.ofMinutes(5)));
        checkAt(DEADLINE.plus(Duration.ofMinutes(30)));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));

        assertThat(boardMessages()).singleElement()
                .satisfies(message -> assertThat(message).contains(MISSING_GAME));
        assertThat(onlyBoardRecord().startedAt()).isEqualTo(DEADLINE.minus(Duration.ofMinutes(30)));
    }

    @Test
    void aPickupOnlyPopularityBacksIsLabelledAsTrendingOnTheFallback() {
        aWaiverWeekWithTheBuffaloGameMissing();
        SleeperStubs.stubTrending(sleeper, "sleeper/trending-add-unbacked.json", "trending-v2");
        String popular = """
                {"playerId": "20103", "reasoning": "Wide Receiver Three is one of the most \
                added players on Sleeper, and his usage this week is not known yet.", \
                "bid": {"low": 1, "target": 2, "high": 3}, "limit": "He is a depth add."}""";
        OutboundStubs.llmAdvisesInOrderOn(llm, BOARD_REQUEST,
                BOARD.replace("\"limit\": \"He is a depth add.\"}]",
                                "\"limit\": \"He is a depth add.\"}, " + popular + "]"));

        checkAt(FALLBACK);

        DecisionRecord record = onlyBoardRecord();
        assertThat(record.candidates())
                .filteredOn(candidate -> candidate.playerId().equals("20103"))
                .singleElement()
                .satisfies(receiver -> assertThat(receiver.included()).isTrue())
                .satisfies(receiver -> assertThat(receiver.reason())
                        .startsWith(TRENDING_ONLY)
                        .contains("most added on Sleeper"));
        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(BOARD_REQUEST))
                .withRequestBody(containing("candidate-20103"))
                .withRequestBody(containing(TRENDING_ONLY))
                .withRequestBody(containing("never present it as usage")));
        assertThat(boardMessages()).singleElement().satisfies(message -> assertThat(message)
                .contains("I've marked any pickup who is on it only because he's trending in adds")
                .contains("Wide Receiver Three · WR")
                .contains("Wide Receiver Three · WR, NE\nHe's trending, but this week's usage "
                        + "numbers aren't in yet."));
    }

    @Test
    void aPickupOnlyPopularityBacksStaysInTheCompleteBoardsPool() {
        aWaiverWeekWithTheBuffaloGameMissing();
        SleeperStubs.stubTrending(sleeper, "sleeper/trending-add-unbacked.json", "trending-v2");
        NflverseStubs.lateSnapCountsArrive(nflverse);
        clock.set(BOARD_DUE);
        feeds.updateIfDue();
        checkRunner.runCheck();

        assertThat(onlyBoardRecord().candidates())
                .filteredOn(candidate -> candidate.playerId().equals("20103"))
                .singleElement()
                .satisfies(receiver -> assertThat(receiver.included()).isTrue());
    }

    @Test
    void sleeperFillsTheMissingGameSoTheNormalBoardGoesOutNamingItsSource() {
        aWaiverWeekWithTheBuffaloGameMissing();
        sleeperHasWeekOne(SLEEPER_WEEK_ONE);

        checkAt(BOARD_DUE);
        assertThat(boardRecords()).isEmpty();
        sleeper.verify(0, getRequestedFor(urlEqualTo(SleeperStubs.STATS_WEEK_1_PATH)));

        checkAt(FALLBACK);

        assertThat(boardMessages()).singleElement().satisfies(message -> assertThat(message)
                .startsWith("Your waiver options\n\n" + OPENING)
                .doesNotContain("aren't all in yet"));
        DecisionRecord record = onlyBoardRecord();
        assertThat(record.evidence())
                .filteredOn(item -> item.id().equals("usage-sleeper-10005"))
                .singleElement()
                .satisfies(item -> assertThat(item.source()).isEqualTo("sleeper:stats"));
        assertThat(record.evidence()).extracting(Evidence::id)
                .doesNotContain("usage-sleeper-10001", "production-sleeper-10001");
        assertThat(record.comparisons().at("/latestWeek/filledFromSleeper/0").asText())
                .isEqualTo(FILLED_GAME);
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(BOARD_REQUEST))
                .withRequestBody(containing(RAY_DAVIS_FROM_SLEEPER))
                .withRequestBody(containing("air-yards share unknown"))
                .withRequestBody(containing(FILLED_GAME))
                .withRequestBody(containing("71% of offensive snaps (47 snaps)")));
        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("45% of offensive snaps (30 snaps)")));
    }

    @Test
    void whenSleeperLacksTheGameTooTheFallbackBoardIsBuilt() {
        aWaiverWeekWithTheBuffaloGameMissing();
        sleeperHasWeekOne("sleeper/stats-2026-1-waivers-late.json");

        checkAt(FALLBACK);

        assertThat(boardMessages()).singleElement().satisfies(message -> assertThat(message)
                .startsWith("Your waiver options\n\nWeek 1's numbers aren't all in yet, "
                        + "because " + MISSING_GAME + "."));
        assertThat(onlyBoardRecord().evidence()).extracting(Evidence::id)
                .noneMatch(id -> id.contains("sleeper"));
    }

    @Test
    void nflverseArrivingLaterReplacesTheValuesSleeperFilled() {
        aWaiverWeekWithTheBuffaloGameMissing();
        sleeperHasWeekOne(SLEEPER_WEEK_ONE);
        OutboundStubs.llmAdvisesInOrderOn(llm, BOARD_REQUEST, BOARD, BOARD);
        checkAt(FALLBACK);

        NflverseStubs.lateSnapCountsArrive(nflverse);
        clock.set(BOARD_DUE.plus(Duration.ofDays(7)));
        feeds.updateIfDue();
        checkRunner.runCheck();

        List<DecisionRecord> rounds = boardRecords();
        assertThat(rounds).hasSize(2);
        assertThat(rounds.getFirst().evidence()).extracting(Evidence::id)
                .contains("usage-sleeper-10005");
        DecisionRecord nextRound = rounds.getLast();
        assertThat(nextRound.evidence()).extracting(Evidence::id)
                .contains("usage-10005")
                .noneMatch(id -> id.contains("sleeper"));
        assertThat(nextRound.comparisons().at("/latestWeek/filledFromSleeper").size()).isZero();
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(BOARD_REQUEST))
                .withRequestBody(containing("65% of offensive snaps (40 snaps)"))
                .withRequestBody(notContaining(RAY_DAVIS_FROM_SLEEPER)));
    }

    private void sleeperHasWeekOne(String fixture) {
        SleeperStubs.stubJson(sleeper, SleeperStubs.STATS_WEEK_1_PATH, fixture, "stats-w1");
    }

    private List<String> boardMessages() {
        DecisionRecord record = onlyBoardRecord();
        assertThat(record.outcome()).isInstanceOf(Outcome.SentBoard.class);
        return ((Outcome.SentBoard) record.outcome()).messages();
    }

    private DecisionRecord onlyBoardRecord() {
        List<DecisionRecord> records = boardRecords();
        assertThat(records).hasSize(1);
        return records.getFirst();
    }

    private List<DecisionRecord> boardRecords() {
        Path league = Path.of(properties.storageDir(), "decision-records", "records",
                "user=" + USER_ID, "league=" + LEAGUE_ID);
        if (!Files.exists(league)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(league)) {
            return files
                    .map(file -> file.getFileName().toString())
                    .map(name -> name.substring("run=".length(), name.length() - ".json".length()))
                    .map(runId -> decisionRecords.read(USER_ID, LEAGUE_ID, runId).orElseThrow())
                    .filter(record -> record.kind() == RunKind.WAIVER_BOARD)
                    .sorted(Comparator.comparing(DecisionRecord::startedAt))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
