package otto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.alerts.MuteStore;
import otto.check.CheckRunner;
import otto.events.EventLog;
import otto.events.EventType;
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
import otto.settings.SettingsStore;
import otto.settings.Trigger;
import otto.storage.OttoJson;
import otto.telegram.TelegramWebhook;
import otto.telegram.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The evidence-based waiver board as the board the user receives, on
 * the waiver week fixture: 2026 week 2, with Bucky Irving named Tampa
 * Bay's starter, Wandale Robinson coming off 13 targets, and the board
 * due at 18:00 Los Angeles time the evening before Wednesday's claims.
 * The scored board keeps running silently as a baseline and is never
 * sent.
 */
class WaiverBoardScenarioTest extends WireSeamTest {

    private static final String USER_ID = "777001";
    private static final String LEAGUE_ID = "1389748403805650944";

    private static final ZoneId LOS_ANGELES = ZoneId.of("America/Los_Angeles");
    private static final Instant TUESDAY_EVENING = ZonedDateTime.of(2026, 9, 15, 18, 0, 0, 0,
            LOS_ANGELES).toInstant();
    private static final Instant FALLBACK = ZonedDateTime.of(2026, 9, 15, 21, 0, 0, 0,
            LOS_ANGELES).toInstant();

    private static final String BOARD_REQUEST = OutboundStubs.WAIVER_BOARD_REQUEST;
    private static final String SCORED_BOARD = "Five waiver targets for Wednesday.";
    private static final String BUTTONS = "$.reply_markup.inline_keyboard";

    private static final String IRVING = """
            {"playerId": "10001", "reasoning": "Bucky Irving played 71% of offensive snaps in \
            his latest game, and his coach named him the starter. He projects 19.0 points this \
            week.", "bid": {"low": 7, "target": 9, "high": 12}, "limit": "Past that you pay \
            for more than a lead role."}""";

    private static final String ROBINSON = """
            {"playerId": "10004", "reasoning": "Wandale Robinson drew 13 targets, a target \
            share of 22%, on 78% of offensive snaps.", "bid": {"low": 3, "target": 5, \
            "high": 8}, "limit": "A receiver this deep in the order rarely draws a bigger bid."}\
            """;

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
    private SettingsStore settings;

    @Autowired
    private MuteStore muteStore;

    @Autowired
    private TelegramWebhook webhook;

    @Autowired
    private EventLog eventLog;

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
        return ("{\"playerId\": \"%s\", \"reasoning\": \"%s\", \"bid\": {\"low\": 1, "
                + "\"target\": 2, \"high\": 3}, \"limit\": \"He is a depth add.\"}")
                .formatted(playerId, reasoning);
    }

    private void aWaiverWeekOnDisk() {
        aWaiverWeekOnDisk("nflverse/games-waivers.csv");
    }

    private void aWaiverWeekOnDisk(String scheduleFixture) {
        SleeperStubs.waiverWeek(sleeper);
        NflverseStubs.waiverWeekReadyForTheBoard(nflverse);
        NflverseStubs.stubCsv(nflverse, NflverseStubs.SCHEDULE_PATH, scheduleFixture);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "A lineup alert.");
        checkRunner.runCheck();
        feeds.updateIfDue();
        defenseBuilder.build();
        researchDiscovery.discover();
        llm.resetAll();
        telegram.resetRequests();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.telegramCallbackAnswered(telegram);
        OutboundStubs.llmPhrases(llm, SCORED_BOARD);
    }

    private void tuesdayEveningWithTheModelAnswering(String... answers) {
        aWaiverWeekOnDisk();
        OutboundStubs.llmAdvisesInOrderOn(llm, BOARD_REQUEST, answers);
        clock.set(TUESDAY_EVENING);
        checkRunner.runCheck();
    }

    @Test
    void theNewBoardIsSentWithItsButtonsAndTheScoredBoardIsNot() {
        tuesdayEveningWithTheModelAnswering(board(false, OPENING, List.of(IRVING, ROBINSON),
                List.of()));

        DecisionRecord record = onlyBoardRecord();
        assertThat(record.kind()).isEqualTo(RunKind.WAIVER_BOARD);
        assertThat(record.exchanges()).singleElement()
                .satisfies(exchange -> assertThat(exchange.checkFailures()).isEmpty());
        List<String> messages = boardMessages();
        assertThat(messages).singleElement().satisfies(message -> assertThat(message)
                .startsWith("Your waiver options\n\n" + OPENING)
                .contains("1. Bucky Irving · RB, TB\nOtto: bid $9 · Suggested range $7–$12.\n"
                        + "Bucky Irving played 71% of offensive snaps")
                .contains("2. Wandale Robinson · WR, NYG\nOtto: bid $5 · Suggested range $3–$8.\n"
                        + "Wandale Robinson drew 13 targets")
                .doesNotContain("View all options")
                .doesNotContainIgnoringCase("score")
                .doesNotContainIgnoringCase("confidence"));

        String alertId = boardAlertId();
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(messages.getFirst())))
                .withRequestBody(matchingJsonPath(BUTTONS + "[0][0].callback_data",
                        equalTo("done:" + alertId)))
                .withRequestBody(matchingJsonPath(BUTTONS + "[0][1].callback_data",
                        equalTo("ignore:" + alertId)))
                .withRequestBody(matchingJsonPath(BUTTONS + "[0][2].callback_data",
                        equalTo("mute:" + alertId))));
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing(SCORED_BOARD)));
    }

    @Test
    void theRecordKeysEachLanesPicksByTheNameEarlierRecordsCarry() {
        tuesdayEveningWithTheModelAnswering(board(false, OPENING, List.of(IRVING, ROBINSON),
                List.of()));

        assertThat(onlyBoardRecord().comparisons().path("lanes").fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("MatchupLane", "NewsLane", "ProductionLane",
                        "ProjectionLane", "TrendingLane", "UsageLane");
    }

    @Test
    void theBoardGoesOutOnceHoweverManyChecksRunInItsWindow() {
        tuesdayEveningWithTheModelAnswering(board(false, OPENING, List.of(IRVING), List.of()));

        clock.set(TUESDAY_EVENING.plus(Duration.ofMinutes(1)));
        checkRunner.runCheck();
        clock.set(TUESDAY_EVENING.plus(Duration.ofHours(2)));
        checkRunner.runCheck();

        assertThat(boardRecords()).hasSize(1);
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(BOARD_REQUEST)));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void aBoardTelegramRefusedGoesOutOnTheNextCheckWithoutAskingTheModelAgain() {
        aWaiverWeekOnDisk();
        OutboundStubs.llmAdvisesInOrderOn(llm, BOARD_REQUEST,
                board(false, OPENING, List.of(IRVING), List.of()));
        telegram.stubFor(post(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .willReturn(aResponse().withStatus(500)));
        clock.set(TUESDAY_EVENING);
        checkRunner.runCheck();

        OutboundStubs.telegramOk(telegram);
        telegram.resetRequests();
        clock.set(TUESDAY_EVENING.plus(Duration.ofMinutes(1)));
        checkRunner.runCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", containing("Your waiver options"))));
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(BOARD_REQUEST)));
    }

    @Test
    void switchingTheWaiverTriggerOffBuildsAndSendsNoBoard() {
        aWaiverWeekOnDisk();
        OutboundStubs.llmAdvisesInOrderOn(llm, BOARD_REQUEST,
                board(false, OPENING, List.of(IRVING), List.of()));
        settings.change(current -> current.withTrigger(Trigger.WAIVER, false));
        clock.set(TUESDAY_EVENING);
        checkRunner.runCheck();

        assertThat(boardRecords()).isEmpty();
        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(BOARD_REQUEST)));
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void theMuteButtonUnderTheBoardSilencesTheNextBoard() {
        tuesdayEveningWithTheModelAnswering(board(false, OPENING, List.of(IRVING), List.of()));

        assertThat(webhook.handle(WEBHOOK_SECRET,
                OutboundStubs.callbackTap("mute:" + boardAlertId()))).isEqualTo(WebhookResult.OK);
        assertThat(muteStore.muted(Trigger.WAIVER.muteTarget())).isTrue();

        telegram.resetRequests();
        clock.set(TUESDAY_EVENING.plus(Duration.ofDays(7)));
        checkRunner.runCheck();
        assertThat(boardRecords()).hasSize(2)
                .filteredOn(record -> !record.startedAt().equals(TUESDAY_EVENING))
                .singleElement()
                .satisfies(muted -> assertThat(muted.outcome()).isInstanceOf(Outcome.DryRun.class));
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("Your waiver options")));
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
    void eachCandidateCarriesHisOpponentFromTheStoredSchedule() {
        aWaiverWeekOnDisk("nflverse/games-waivers-matchups.csv");
        OutboundStubs.llmAdvisesInOrderOn(llm, BOARD_REQUEST,
                board(false, OPENING, List.of(IRVING, ROBINSON), List.of()));
        clock.set(TUESDAY_EVENING);
        checkRunner.runCheck();

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(BOARD_REQUEST))
                .withRequestBody(containing("matchup-opponent-10004"))
                .withRequestBody(containing("\\\"opponent\\\":\\\"NO\\\""))
                .withRequestBody(containing("matchup-defense-points-allowed-10004")));
        DecisionRecord record = onlyBoardRecord();
        assertThat(record.evidence()).anySatisfy(evidence -> {
            assertThat(evidence.id()).isEqualTo("matchup-opponent-10004");
            assertThat(evidence.source()).isEqualTo("nflverse:schedules");
            assertThat(evidence.publishedAt()).isPresent();
        });
        assertThat(record.outcome()).isInstanceOf(Outcome.SentBoard.class);
    }

    @Test
    void aStatCorrectionBeforeTheBoardIsBuiltIsWhatTheBoardIsBuiltOn() {
        aWaiverWeekOnDisk();
        NflverseStubs.waiverWeekStatsCorrected(nflverse);
        clock.set(TUESDAY_EVENING.minus(Duration.ofMinutes(30)));
        feeds.updateIfDue();
        String corrected = ROBINSON.replace("13 targets, a target share of 22%",
                "11 targets, a target share of 19%");
        OutboundStubs.llmAdvisesInOrderOn(llm, BOARD_REQUEST,
                board(false, OPENING, List.of(IRVING, corrected), List.of()));
        clock.set(TUESDAY_EVENING);
        checkRunner.runCheck();

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("11 targets of 20 team targets, a target share of 19%")));
        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("13 targets of")));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text",
                        containing("Wandale Robinson drew 11 targets"))));
    }

    @Test
    void aWrongNumberIsCorrectedOnceAndTheCorrectedBoardIsSent() {
        tuesdayEveningWithTheModelAnswering(
                board(false, OPENING, List.of(IRVING.replace("71%", "85%")), List.of()),
                board(false, OPENING, List.of(IRVING), List.of()));

        DecisionRecord record = onlyBoardRecord();
        assertThat(record.exchanges()).hasSize(2);
        assertThat(record.exchanges().getFirst().checkFailures())
                .anySatisfy(failure -> assertThat(failure).contains("85"));
        assertThat(String.join("\n\n", boardMessages())).contains("71%").doesNotContain("85%");
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("85%")));
    }

    @Test
    void aBoardThatFailsItsChecksTwiceIsWithheldAndNeverReplacedByTheScoredBoard() {
        String cook = entry("8138", "James Cook is a strong add.");
        tuesdayEveningWithTheModelAnswering(board(false, OPENING, List.of(cook), List.of()));

        DecisionRecord record = onlyBoardRecord();
        assertThat(record.exchanges()).hasSize(2)
                .allSatisfy(exchange -> assertThat(exchange.checkFailures())
                        .anySatisfy(failure -> assertThat(failure)
                                .contains("8138 is not a free agent")));
        assertThat(record.outcome()).isInstanceOf(Outcome.WithheldWithNotice.class);
        assertThat(((Outcome.WithheldWithNotice) record.outcome()).reason())
                .startsWith("The board failed its checks twice");
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text",
                        containing("No waiver board this round")))
                .withRequestBody(matchingJsonPath("$.text", containing("past my checks"))));
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("James Cook")));
    }

    @Test
    void aModelThatCannotBeReachedIsTriedAgainUntilTheBufferThenTheRoundIsWithheld() {
        aWaiverWeekOnDisk();
        OutboundStubs.llmRefuses(llm);
        clock.set(TUESDAY_EVENING);
        checkRunner.runCheck();

        assertThat(boardRecords()).isEmpty();
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));

        clock.set(FALLBACK);
        checkRunner.runCheck();

        assertThat(onlyBoardRecord().outcome()).isInstanceOf(Outcome.WithheldWithNotice.class);
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text",
                        containing("No waiver board this round")))
                .withRequestBody(matchingJsonPath("$.text",
                        containing("could not reach the model"))));
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("past my checks")));
    }

    @Test
    void aRecordThatCannotBeSavedNeverDrivesAModelCallEveryMinute() throws IOException {
        aWaiverWeekOnDisk();
        Path league = Path.of(properties.storageDir(), "decision-records", "records",
                "user=" + USER_ID, "league=" + LEAGUE_ID);
        if (Files.exists(league)) {
            try (Stream<Path> files = Files.walk(league)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
        Files.createDirectories(league.getParent());
        Files.writeString(league, "a file where the league's records would go");
        OutboundStubs.llmAdvisesInOrderOn(llm, BOARD_REQUEST,
                board(false, OPENING, List.of(IRVING), List.of()));
        clock.set(TUESDAY_EVENING);
        checkRunner.runCheck();
        clock.set(TUESDAY_EVENING.plus(Duration.ofMinutes(1)));
        checkRunner.runCheck();
        clock.set(TUESDAY_EVENING.plus(Duration.ofMinutes(2)));
        checkRunner.runCheck();

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(BOARD_REQUEST)));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", containing("Your waiver options"))));
    }

    @Test
    void aRoundThatCannotBeScreenedWaitsThenSaysWhatIsMissingInsideTheBuffer() throws IOException {
        aWaiverWeekOnDisk();
        Files.delete(Path.of(properties.storageDir(), HexFormat.of()
                .formatHex("player-directory".getBytes(StandardCharsets.UTF_8)) + ".json"));
        sleeper.stubFor(get(urlEqualTo(SleeperStubs.PLAYERS_PATH))
                .willReturn(aResponse().withStatus(500)));
        clock.set(TUESDAY_EVENING);
        checkRunner.runCheck();

        assertThat(boardRecords()).isEmpty();
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("No waiver board this round")));

        clock.set(FALLBACK);
        checkRunner.runCheck();
        checkRunner.runCheck();

        assertThat(onlyBoardRecord().outcome()).isInstanceOf(Outcome.WithheldWithNotice.class);
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text",
                        containing("No waiver board this round")))
                .withRequestBody(matchingJsonPath("$.text",
                        containing("there is no player directory yet"))));
        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(BOARD_REQUEST)));
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
        assertThat(record.outcome()).isInstanceOf(Outcome.SentBoard.class);
    }

    @Test
    void aBoardWithNothingWorthClaimingOpensWithTheHold() {
        String hold = "Hold this week and save your FAAB: your starters are covered.";
        tuesdayEveningWithTheModelAnswering(board(true, hold, List.of(),
                List.of(entry("10003", "Cade Otton is a speculative tight end stash."))));

        List<String> messages = boardMessages();
        assertThat(messages).singleElement().satisfies(message -> assertThat(message)
                .startsWith("Your waiver options\n\n" + hold
                        + "\n\nIf you still want to make a move\n\n1. Cade Otton · TE, TB"));
    }

    @Test
    void aLongBoardShowsFiveEntriesAndItsButtonSendsTheRest() {
        List<String> six = Stream.of("10001", "10004", "10003", "10005", "10002", "20101")
                .map(playerId -> entry(playerId, "He has a real role and is worth a look."))
                .toList();
        tuesdayEveningWithTheModelAnswering(board(false, OPENING, six, List.of()));

        String message = String.join("\n\n", boardMessages());
        assertThat(message).contains("5. ").doesNotContain("6. ")
                .endsWith("View all options: 1 more player on the full board.");
        String viewAll = "all:" + boardAlertId();
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath(BUTTONS + "[1][0].callback_data",
                        equalTo(viewAll))));

        telegram.resetRequests();
        assertThat(webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap(viewAll)))
                .isEqualTo(WebhookResult.OK);

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", containing("All your waiver options")))
                .withRequestBody(matchingJsonPath("$.text", containing("6. ")))
                .withRequestBody(matchingJsonPath("$.text", containing("1. Bucky Irving"))));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.ANSWER_CALLBACK_PATH))
                .withRequestBody(matchingJsonPath("$.text",
                        equalTo("Here is every option on that board."))));
    }

    @Test
    void aFullListCutShortByTelegramResumesWhereItStoppedOnTheNextTap() {
        String reasoning = "He has a real role and is worth a look. ".repeat(17).strip();
        List<String> six = Stream.of("10001", "10004", "10003", "10005", "10002", "20101")
                .map(playerId -> entry(playerId, reasoning))
                .toList();
        tuesdayEveningWithTheModelAnswering(board(false, OPENING, six, List.of()));
        String viewAll = "all:" + boardAlertId();
        telegram.resetRequests();
        telegram.stubFor(post(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .atPriority(1)
                .withRequestBody(matchingJsonPath("$.text", containing("6. ")))
                .willReturn(aResponse().withStatus(500)));

        webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap(viewAll));

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.ANSWER_CALLBACK_PATH))
                .withRequestBody(matchingJsonPath("$.text",
                        equalTo("I could not send the full list. Tap again in a minute."))));
        telegram.resetAll();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.telegramCallbackAnswered(telegram);

        webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap(viewAll));

        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text",
                        containing("All your waiver options"))));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", containing("6. "))));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.ANSWER_CALLBACK_PATH))
                .withRequestBody(matchingJsonPath("$.text",
                        equalTo("Here is every option on that board."))));
    }

    @Test
    void aBoardWhoseRecordCannotBeReadIsAnsweredRatherThanRedelivered() throws IOException {
        List<String> six = Stream.of("10001", "10004", "10003", "10005", "10002", "20101")
                .map(playerId -> entry(playerId, "He has a real role and is worth a look."))
                .toList();
        tuesdayEveningWithTheModelAnswering(board(false, OPENING, six, List.of()));
        String viewAll = "all:" + boardAlertId();
        Path league = Path.of(properties.storageDir(), "decision-records", "records",
                "user=" + USER_ID, "league=" + LEAGUE_ID);
        try (Stream<Path> files = Files.walk(league)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
        Files.writeString(league, "a file where the league's records would go");
        telegram.resetRequests();

        assertThat(webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap(viewAll)))
                .isEqualTo(WebhookResult.OK);

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.ANSWER_CALLBACK_PATH))
                .withRequestBody(matchingJsonPath("$.text",
                        equalTo("I could not send the full list. Tap again in a minute."))));
    }

    @Test
    void aBoardOfFiveOrFewerHasNoViewAllOptionsButton() {
        tuesdayEveningWithTheModelAnswering(board(false, OPENING, List.of(IRVING), List.of()));

        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("all:")));
        telegram.resetRequests();
        webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap("all:" + boardAlertId()));
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void aBoardLongerThanOneTelegramMessageGoesOutInPartsWithTheButtonsUnderTheLast() {
        String reasoning = "He has a real role and is worth a look. ".repeat(17).strip();
        String opening = "Your roster has room for a real upgrade this week. ".repeat(13).strip();
        List<String> five = Stream.of("10001", "10004", "10003", "10005", "10002")
                .map(playerId -> entry(playerId, reasoning))
                .toList();
        tuesdayEveningWithTheModelAnswering(board(false, opening, five, List.of()));

        List<String> messages = boardMessages();
        assertThat(messages).hasSizeGreaterThan(1)
                .allSatisfy(message -> assertThat(message.length()).isLessThan(4096));
        assertThat(messages.get(1)).matches("(?s)^\\d\\. .*");
        List<LoggedRequest> sent = telegram.findAll(
                postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(sent).extracting(request -> body(request)
                        .path("text").asText())
                .containsExactlyElementsOf(messages);
        assertThat(sent).extracting(request -> body(request)
                        .has("reply_markup"))
                .containsExactlyElementsOf(Stream.concat(
                        Stream.generate(() -> false).limit(messages.size() - 1),
                        Stream.of(true)).toList());
    }

    private String boardAlertId() {
        return eventLog.all().stream()
                .filter(event -> event.type() == EventType.ALERT_SENT
                        && event.key().startsWith("alert:waiver:"))
                .map(event -> event.facts().get("alertId"))
                .findFirst()
                .orElseThrow();
    }

    private static JsonNode body(LoggedRequest request) {
        try {
            return OttoJson.MAPPER.readTree(request.getBodyAsString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
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
