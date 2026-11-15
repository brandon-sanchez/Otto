package otto;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import otto.check.CheckRunner;
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

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rollback: with {@code otto.sent-waiver-board} set to SCORED, the
 * scored board goes out, and the evidence-based board is still built
 * and recorded every round, unsent, so switching back loses no week.
 */
@TestPropertySource(properties = "otto.sent-waiver-board=SCORED")
class WaiverBoardRollbackScenarioTest extends WireSeamTest {

    private static final String USER_ID = "777001";
    private static final String LEAGUE_ID = "1389748403805650944";

    private static final Instant TUESDAY_EVENING = ZonedDateTime.of(2026, 9, 15, 18, 0, 0, 0,
            ZoneId.of("America/Los_Angeles")).toInstant();

    private static final String BOARD = """
            {"hold": false, "opening": "A pickup stands out this week.",
             "picks": [{"playerId": "10001", "reasoning": "Bucky Irving played 71% of \
            offensive snaps.", "bid": {"low": 1, "target": 2, "high": 3}, \
            "limit": "He is a depth add."}],
             "optional": [], "claims": []}
            """;

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private DecisionRecords decisionRecords;

    @Autowired
    private OttoProperties properties;

    @Test
    void theScoredBoardIsSentAndTheEvidenceBoardIsRecordedAsADryRun() {
        SleeperStubs.waiverWeek(sleeper);
        NflverseStubs.waiverWeekReadyForTheBoard(nflverse);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "A lineup alert.");
        checkRunner.runCheck();
        feeds.updateIfDue();
        telegram.resetRequests();
        OutboundStubs.llmPhrases(llm, "Five waiver targets for Wednesday.");
        OutboundStubs.llmAdvisesInOrderOn(llm, OutboundStubs.WAIVER_BOARD_REQUEST, BOARD);

        clock.set(TUESDAY_EVENING);
        checkRunner.runCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text",
                        containing("Five waiver targets for Wednesday."))));
        List<DecisionRecord> boards = StoredRecords.of(decisionRecords,
                properties.storageDir(), USER_ID, LEAGUE_ID, RunKind.WAIVER_BOARD);
        assertThat(boards).hasSize(1);
        DecisionRecord record = boards.getFirst();
        assertThat(record.outcome()).isInstanceOf(Outcome.DryRun.class);
        assertThat(((Outcome.DryRun) record.outcome()).messages()).singleElement()
                .satisfies(message -> assertThat(message).startsWith("Your waiver options"));
    }

    @Test
    void oneCheckRanksTheScoredBoardOnceForTheAlertAndTheBaselines() {
        aWaiverWeekOnDisk(BOARD);
        sleeper.resetRequests();

        clock.set(TUESDAY_EVENING);
        checkRunner.runCheck();

        sleeper.verify(1, getRequestedFor(urlPathEqualTo("/players/nfl/10001/news")));
    }

    @Test
    void aWithheldDryRunRecordsNoNoticeThatAFlipBackCouldSend() {
        String rostered = BOARD.replace("10001", "8138");
        aWaiverWeekOnDisk(rostered);

        clock.set(TUESDAY_EVENING);
        checkRunner.runCheck();

        DecisionRecord record = StoredRecords.of(decisionRecords, properties.storageDir(),
                USER_ID, LEAGUE_ID, RunKind.WAIVER_BOARD).getFirst();
        assertThat(record.outcome()).isInstanceOf(Outcome.Withheld.class);
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("No waiver board this round")));
    }

    private void aWaiverWeekOnDisk(String board) {
        SleeperStubs.waiverWeek(sleeper);
        NflverseStubs.waiverWeekReadyForTheBoard(nflverse);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "A lineup alert.");
        checkRunner.runCheck();
        feeds.updateIfDue();
        telegram.resetRequests();
        OutboundStubs.llmPhrases(llm, "Five waiver targets for Wednesday.");
        OutboundStubs.llmAdvisesInOrderOn(llm, OutboundStubs.WAIVER_BOARD_REQUEST, board);
    }
}
