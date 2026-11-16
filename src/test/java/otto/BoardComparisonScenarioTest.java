package otto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.board.BoardStrategy;
import otto.board.WeeklyComparison;
import otto.check.CheckRunner;
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
import otto.settings.SettingsStore;
import otto.settings.Trigger;
import otto.storage.OttoJson;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The weekly comparison of the waiver boards on the waiver week
 * fixture: the evidence-based board and its baselines are recorded the evening
 * before Wednesday's claims for 2026 week 2, and judged once week 2's
 * stats are complete and the stat-correction window has closed.
 */
class BoardComparisonScenarioTest extends WireSeamTest {

    private static final String USER_ID = "777001";
    private static final String LEAGUE_ID = "1389748403805650944";

    private static final ZoneId LOS_ANGELES = ZoneId.of("America/Los_Angeles");

    private static final Instant TUESDAY_EVENING =
            ZonedDateTime.of(2026, 9, 15, 18, 0, 0, 0, LOS_ANGELES).toInstant();

    /** Thursday after week 2's Monday game: corrections may still land. */
    private static final Instant THURSDAY_AFTER =
            ZonedDateTime.of(2026, 9, 24, 12, 0, 0, 0, LOS_ANGELES).toInstant();

    private static final Instant FRIDAY_AFTER =
            ZonedDateTime.of(2026, 9, 25, 12, 0, 0, 0, LOS_ANGELES).toInstant();

    private static final String BOARD = """
            {
              "hold": false,
              "opening": "A pickup stands out this week, and a receiver is worth a claim.",
              "picks": [
                {"playerId": "10001", "reasoning": "Bucky Irving played 71% of offensive snaps.", \
                "bid": {"low": 1, "target": 2, "high": 3}, "limit": "He is a depth add."},
                {"playerId": "10004", "reasoning": "Wandale Robinson drew 13 targets.", \
                "bid": {"low": 1, "target": 2, "high": 3}, "limit": "He is a depth add."}
              ],
              "optional": [],
              "claims": [
                {"text": "Bucky Irving played 71% of offensive snaps", "evidence": ["usage-10001"]}
              ]
            }
            """;

    private static final String ROSTERED_PICK = BOARD.replace("10004", "8138")
            .replace("Wandale Robinson drew 13 targets.", "James Cook is a strong add.");

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

    @Autowired
    private SettingsStore settings;

    private void tuesdayEveningWithTheModelAnswering(String... answers) {
        SleeperStubs.waiverWeek(sleeper);
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

    private void weekTwoIsPlayed() {
        NflverseStubs.stubJson(nflverse, NflverseStubs.SCHEDULE_RELEASE_PATH,
                "nflverse/release-schedules-week-2-played.json");
        NflverseStubs.stubCsv(nflverse, NflverseStubs.SCHEDULE_PATH,
                "nflverse/games-waivers-week-2-played.csv");
        NflverseStubs.stubJson(nflverse, NflverseStubs.STATS_RELEASE_PATH,
                "nflverse/release-stats-player-refreshed.json");
        NflverseStubs.stubCsv(nflverse, NflverseStubs.STATS_2026_PATH,
                "nflverse/stats-player-week-waivers-week-2-played.csv");
    }

    private void checkAt(Instant at) {
        clock.set(at);
        feeds.updateIfDue();
        checkRunner.runCheck();
    }

    @Test
    void theOldScorerAndEveryBaselineAreRecordedBesideTheNewBoardAndNothingMoreIsSent() {
        tuesdayEveningWithTheModelAnswering(BOARD);

        assertThat(records(RunKind.WAIVER_BOARD)).hasSize(1);
        DecisionRecord baselines = onlyBaselines();
        assertThat(baselines.outcome()).isInstanceOf(Outcome.Silent.class);
        JsonNode picks = baselines.comparisons().path("picks");
        assertThat(picks.path("SCORED_BOARD")).isNotEmpty();
        assertThat(picks.path("NO_PICKUP")).isEmpty();
        assertThat(picks.path("PLAYER_RANK")).isNotEmpty();
        assertThat(picks.path("NEED_FIRST")).isNotEmpty();
        assertThat(ids(picks.path("TOP_TRENDING")))
                .containsExactly("10001", "10004", "5850", "8138", "10003");

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text",
                        containing("Five waiver targets for Wednesday."))));
    }

    @Test
    void theBaselinesAreRecordedEvenWhileTheWaiverTriggerIsOff() {
        settings.change(current -> current.withTrigger(Trigger.WAIVER, false));

        tuesdayEveningWithTheModelAnswering(BOARD);

        assertThat(onlyBaselines().comparisons().path("picks").path("SCORED_BOARD"))
                .isNotEmpty();
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("Five waiver targets")));
    }

    @Test
    void theRoundIsJudgedOnWhatTheBoardsPicksActuallyAddedToTheLineup() {
        tuesdayEveningWithTheModelAnswering(BOARD);
        weekTwoIsPlayed();

        checkAt(FRIDAY_AFTER);

        WeeklyComparison comparison = comparison().orElseThrow();
        assertThat(comparison.season()).isEqualTo("2026");
        assertThat(comparison.week()).isEqualTo(2);
        assertThat(comparison.strategies()).extracting(WeeklyComparison.StrategyResult::strategy)
                .containsExactly(BoardStrategy.values());
        assertThat(comparison.decisions()).isEqualTo(6);
        assertThat(comparison.decisionsCovered()).isEqualTo(6);

        WeeklyComparison.StrategyResult board = strategy(comparison, BoardStrategy.EVIDENCE_BOARD);
        assertThat(board.picks()).extracting(WeeklyComparison.PickResult::playerId)
                .containsExactly("10001", "10004");
        assertThat(board.picks().getFirst().points()).contains(21.5);
        assertThat(board.leadGain()).contains(21.5);
        assertThat(board.meanGain()).contains(16.75);

        WeeklyComparison.StrategyResult none = strategy(comparison, BoardStrategy.NO_PICKUP);
        assertThat(none.covered()).isTrue();
        assertThat(none.proposed()).isZero();
        assertThat(none.leadGain()).contains(0.0);
    }

    @Test
    void aBoardRecordThatKeepsNoPicksIsJudgedOnItsLastCheckedAnswer() throws IOException {
        tuesdayEveningWithTheModelAnswering(BOARD);
        Path board = recordFile(records(RunKind.WAIVER_BOARD).getFirst().runId());
        ObjectNode stored = (ObjectNode) OttoJson.MAPPER.readTree(board.toFile());
        ((ObjectNode) stored.path("outcome")).remove("picks");
        OttoJson.MAPPER.writeValue(board.toFile(), stored);
        weekTwoIsPlayed();

        checkAt(FRIDAY_AFTER);

        assertThat(strategy(comparison().orElseThrow(), BoardStrategy.EVIDENCE_BOARD).picks())
                .extracting(WeeklyComparison.PickResult::playerId)
                .containsExactly("10001", "10004");
    }

    @Test
    void onlyPlayersWhoWereAddableAndLegalWhenTheBoardWasBuiltCount() {
        tuesdayEveningWithTheModelAnswering(BOARD);
        weekTwoIsPlayed();

        checkAt(FRIDAY_AFTER);

        WeeklyComparison.StrategyResult trending =
                strategy(comparison().orElseThrow(), BoardStrategy.TOP_TRENDING);
        assertThat(trending.proposed()).isEqualTo(5);
        assertThat(trending.counted()).isEqualTo(3);
        assertThat(trending.picks())
                .filteredOn(pick -> pick.playerId().equals("5850")
                        || pick.playerId().equals("8138"))
                .hasSize(2)
                .allSatisfy(pick -> {
                    assertThat(pick.addable()).isFalse();
                    assertThat(pick.lineupGain()).isEmpty();
                    assertThat(pick.excluded()).hasValueSatisfying(reason ->
                            assertThat(reason).contains("on a roster in the league"));
                });
    }

    @Test
    void aWithheldBoardIsAnUncoveredDecision() {
        tuesdayEveningWithTheModelAnswering(ROSTERED_PICK, ROSTERED_PICK);
        weekTwoIsPlayed();

        checkAt(FRIDAY_AFTER);

        WeeklyComparison comparison = comparison().orElseThrow();
        WeeklyComparison.StrategyResult board = strategy(comparison, BoardStrategy.EVIDENCE_BOARD);
        assertThat(board.covered()).isFalse();
        assertThat(board.note()).hasValueSatisfying(note ->
                assertThat(note).contains("withheld"));
        assertThat(comparison.decisionsCovered()).isEqualTo(5);
    }

    @Test
    void nothingIsJudgedUntilTheWeekIsCompleteAndItsCorrectionsAreIn() {
        tuesdayEveningWithTheModelAnswering(BOARD);

        checkAt(FRIDAY_AFTER);
        assertThat(comparison()).isEmpty();

        weekTwoIsPlayed();
        checkAt(THURSDAY_AFTER);
        assertThat(comparison()).isEmpty();
    }

    private Optional<WeeklyComparison> comparison() {
        return decisionRecords.readOutcome(USER_ID, LEAGUE_ID, onlyBaselines().runId(),
                WeeklyComparison.class);
    }

    private static WeeklyComparison.StrategyResult strategy(WeeklyComparison comparison,
            BoardStrategy strategy) {
        return comparison.strategies().stream()
                .filter(result -> result.strategy() == strategy)
                .findFirst()
                .orElseThrow();
    }

    private static List<String> ids(JsonNode picks) {
        return Stream.iterate(0, index -> index < picks.size(), index -> index + 1)
                .map(index -> picks.get(index).path("playerId").asText())
                .toList();
    }

    private DecisionRecord onlyBaselines() {
        List<DecisionRecord> baselines = records(RunKind.WAIVER_BASELINES);
        assertThat(baselines).hasSize(1);
        return baselines.getFirst();
    }

    private Path recordFile(String runId) {
        return Path.of(properties.storageDir(), "decision-records", "records",
                "user=" + USER_ID, "league=" + LEAGUE_ID, "run=" + runId + ".json");
    }

    private List<DecisionRecord> records(RunKind kind) {
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
                    .filter(record -> record.kind() == kind)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
