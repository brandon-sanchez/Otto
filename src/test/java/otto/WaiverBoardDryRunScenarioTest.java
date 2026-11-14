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
import java.util.Set;
import java.util.stream.Collectors;
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
import otto.records.Candidate;
import otto.records.DecisionRecord;
import otto.records.DecisionRecords;
import otto.records.Outcome;
import otto.records.RunKind;
import otto.research.ResearchDiscovery;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The evidence-based waiver board in dry-run mode, on the waiver week
 * fixture: 2026 week 2, with Bucky Irving named Tampa Bay's starter,
 * Wandale Robinson coming off 13 targets, and the board
 * {@code WaiverAlertService} sends due at 18:00 Los Angeles time the
 * evening before Wednesday's claims.
 */
class WaiverBoardDryRunScenarioTest extends WireSeamTest {

    private static final String USER_ID = "777001";
    private static final String LEAGUE_ID = "1389748403805650944";

    private static final Instant TUESDAY_EVENING = ZonedDateTime.of(2026, 9, 15, 18, 0, 0, 0,
            ZoneId.of("America/Los_Angeles")).toInstant();

    private static final String BOARD_REQUEST = OutboundStubs.WAIVER_BOARD_REQUEST;

    private static final String IRVING = """
            {"playerId": "10001", "reasoning": "Bucky Irving played 71% of offensive snaps in \
            his latest game, and his coach named him the starter. He projects 19.0 points this \
            week."}""";

    private static final String ROBINSON = """
            {"playerId": "10004", "reasoning": "Wandale Robinson drew 13 targets, a target \
            share of 22%, on 78% of offensive snaps."}""";

    private static final String OPENING = "A pickup stands out this week, and a receiver "
            + "is worth a claim behind him.";

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

    private static String board(boolean hold, String opening, List<String> picks,
            List<String> optional) {
        return """
                {
                  "hold": %s,
                  "opening": "%s",
                  "picks": [%s],
                  "optional": [%s],
                  "claims": [
                    {"text": "Bucky Irving played 71%% of offensive snaps", "evidence": ["usage-10001"]}
                  ]
                }
                """.formatted(hold, opening, String.join(",", picks), String.join(",", optional));
    }

    private static String entry(String playerId, String reasoning) {
        return "{\"playerId\": \"%s\", \"reasoning\": \"%s\"}".formatted(playerId, reasoning);
    }

    private void aWaiverWeekOnDisk() {
        SleeperStubs.waiverWeek(sleeper);
        NflverseStubs.waiverWeekWithThePlayerRegister(nflverse);
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
    }

    private void tuesdayEveningWithTheModelAnswering(String... answers) {
        aWaiverWeekOnDisk();
        OutboundStubs.llmAdvisesInOrderOn(llm, BOARD_REQUEST, answers);
        clock.set(TUESDAY_EVENING);
        checkRunner.runCheck();
    }

    @Test
    void theNewBoardIsBuiltAndRecordedWhileOnlyTheOldBoardIsSent() {
        tuesdayEveningWithTheModelAnswering(board(false, OPENING, List.of(IRVING, ROBINSON),
                List.of()));

        DecisionRecord record = onlyBoardRecord();
        assertThat(record.kind()).isEqualTo(RunKind.WAIVER_BOARD);
        assertThat(record.exchanges()).singleElement()
                .satisfies(exchange -> assertThat(exchange.checkFailures()).isEmpty());
        assertThat(record.outcome()).isInstanceOf(Outcome.DryRun.class);
        List<String> messages = ((Outcome.DryRun) record.outcome()).messages();
        assertThat(messages).singleElement().satisfies(message -> assertThat(message)
                .startsWith("Your waiver options\n\n" + OPENING)
                .contains("1. Bucky Irving · RB, TB\nBucky Irving played 71% of offensive snaps")
                .contains("2. Wandale Robinson · WR, NYG\nWandale Robinson drew 13 targets")
                .doesNotContain("View all options")
                .doesNotContainIgnoringCase("score")
                .doesNotContainIgnoringCase("confidence"));

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", containing("Your waiver options"))));
    }

    @Test
    void everyFreeAgentIsScreenedAndNoRosteredPlayerIsACandidate() {
        tuesdayEveningWithTheModelAnswering(board(false, OPENING, List.of(IRVING), List.of()));

        DecisionRecord record = onlyBoardRecord();
        Set<String> screened = record.candidates().stream().map(Candidate::playerId)
                .collect(Collectors.toSet());
        assertThat(screened).hasSize(22)
                .contains("10001", "10002", "10003", "10004", "10005", "10006", "20210")
                .doesNotContain("4046", "5850", "8138", "6813");
        assertThat(record.candidates())
                .filteredOn(candidate -> candidate.playerId().equals("10001"))
                .singleElement()
                .satisfies(irving -> assertThat(irving.included()).isTrue())
                .satisfies(irving -> assertThat(irving.reason())
                        .contains("snap share", "highest-projected", "most added"));
        assertThat(record.candidates()).filteredOn(candidate -> !candidate.included())
                .allSatisfy(left -> assertThat(left.reason()).isNotBlank());
    }

    @Test
    void theModelIsHandedEveryKindOfEvidenceAndUnknownsAreNeverZero() {
        tuesdayEveningWithTheModelAnswering(board(false, OPENING, List.of(IRVING), List.of()));

        DecisionRecord record = onlyBoardRecord();
        assertThat(record.evidence()).extracting(evidence -> evidence.id())
                .contains("board", "needs", "roster", "candidate-10001", "usage-10001",
                        "production-10004", "projection-10001", "news-10001-1",
                        "trending-10001");
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(BOARD_REQUEST))
                .withRequestBody(containing("71% of offensive snaps (47 snaps)"))
                .withRequestBody(containing("0 targets of 18 team targets"))
                .withRequestBody(containing("red-zone and end-zone work unknown"))
                .withRequestBody(containing("expected points unknown"))
                .withRequestBody(containing("Named the starter in Tampa Bay"))
                .withRequestBody(containing("13 targets of"))
                .withRequestBody(containing("19.0")));
    }

    @Test
    void aClaimRoundIsBuiltOnceHoweverManyChecksRunInItsWindow() {
        tuesdayEveningWithTheModelAnswering(board(false, OPENING, List.of(IRVING), List.of()));

        clock.set(TUESDAY_EVENING.plus(Duration.ofMinutes(1)));
        checkRunner.runCheck();
        clock.set(TUESDAY_EVENING.plus(Duration.ofHours(2)));
        checkRunner.runCheck();

        assertThat(boardRecords()).hasSize(1);
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(BOARD_REQUEST)));
    }

    @Test
    void nothingIsBuiltBeforeTheBoardIsDue() {
        aWaiverWeekOnDisk();
        OutboundStubs.llmAdvisesInOrderOn(llm, BOARD_REQUEST,
                board(false, OPENING, List.of(IRVING), List.of()));
        clock.set(TUESDAY_EVENING.minus(Duration.ofMinutes(1)));
        checkRunner.runCheck();

        assertThat(boardRecords()).isEmpty();
    }

    @Test
    void aWrongNumberIsCorrectedOnce() {
        tuesdayEveningWithTheModelAnswering(
                board(false, OPENING, List.of(IRVING.replace("71%", "85%")), List.of()),
                board(false, OPENING, List.of(IRVING), List.of()));

        DecisionRecord record = onlyBoardRecord();
        assertThat(record.exchanges()).hasSize(2);
        assertThat(record.exchanges().getFirst().checkFailures())
                .anySatisfy(failure -> assertThat(failure).contains("85"));
        assertThat(record.outcome()).isInstanceOf(Outcome.DryRun.class);
    }

    @Test
    void aRosteredPlayerRecommendedTwiceWithholdsTheBoardAndTheOldBoardStillGoes() {
        String cook = entry("8138", "James Cook is a strong add.");
        tuesdayEveningWithTheModelAnswering(board(false, OPENING, List.of(cook), List.of()));

        DecisionRecord record = onlyBoardRecord();
        assertThat(record.exchanges()).hasSize(2)
                .allSatisfy(exchange -> assertThat(exchange.checkFailures())
                        .anySatisfy(failure -> assertThat(failure)
                                .contains("8138 is not a free agent")));
        assertThat(record.outcome()).isInstanceOf(Outcome.Withheld.class);
        assertThat(((Outcome.Withheld) record.outcome()).reason())
                .startsWith("The board failed its checks twice");
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void aScoreOrAConfidenceLabelIsSentBackForCorrection() {
        tuesdayEveningWithTheModelAnswering(
                board(false, OPENING + " Bucky Irving has a score of 100 and High confidence.",
                        List.of(IRVING), List.of()),
                board(false, OPENING, List.of(IRVING), List.of()));

        DecisionRecord record = onlyBoardRecord();
        assertThat(record.exchanges().getFirst().checkFailures())
                .anySatisfy(failure -> assertThat(failure).contains("score"))
                .anySatisfy(failure -> assertThat(failure).contains("confidence label"));
        assertThat(record.outcome()).isInstanceOf(Outcome.DryRun.class);
    }

    @Test
    void aBoardWithNothingWorthClaimingOpensWithTheHold() {
        String hold = "Hold this week and save your FAAB: your starters are covered.";
        tuesdayEveningWithTheModelAnswering(board(true, hold, List.of(),
                List.of(entry("10003", "Cade Otton is a speculative tight end stash."))));

        List<String> messages = dryRunMessages();
        assertThat(messages).singleElement().satisfies(message -> assertThat(message)
                .startsWith("Your waiver options\n\n" + hold
                        + "\n\nIf you still want to make a move\n\n1. Cade Otton · TE, TB"));
    }

    @Test
    void aLongBoardShowsFiveEntriesAndPointsToTheRest() {
        List<String> six = Stream.of("10001", "10004", "10003", "10005", "10002", "20101")
                .map(playerId -> entry(playerId, "He has a real role and is worth a look."))
                .toList();
        tuesdayEveningWithTheModelAnswering(board(false, OPENING, six, List.of()));

        String message = String.join("\n\n", dryRunMessages());
        assertThat(message).contains("5. ").doesNotContain("6. ")
                .endsWith("View all options: 1 more player on the full board.");
    }

    @Test
    void aBoardLongerThanOneTelegramMessageSplitsBetweenEntries() {
        String reasoning = "He has a real role and is worth a look. ".repeat(17).strip();
        String opening = "Your roster has room for a real upgrade this week. ".repeat(13).strip();
        List<String> five = Stream.of("10001", "10004", "10003", "10005", "10002")
                .map(playerId -> entry(playerId, reasoning))
                .toList();
        tuesdayEveningWithTheModelAnswering(board(false, opening, five, List.of()));

        List<String> messages = dryRunMessages();
        assertThat(messages).hasSizeGreaterThan(1)
                .allSatisfy(message -> assertThat(message.length()).isLessThan(4096));
        assertThat(messages.get(1)).matches("(?s)^\\d\\. .*");
    }

    private List<String> dryRunMessages() {
        DecisionRecord record = onlyBoardRecord();
        assertThat(record.outcome()).isInstanceOf(Outcome.DryRun.class);
        return ((Outcome.DryRun) record.outcome()).messages();
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
