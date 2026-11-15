package otto;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.harness.Fixtures;
import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.RealModel;
import otto.harness.SleeperStubs;
import otto.harness.StoredRecords;
import otto.harness.WireSeamTest;
import otto.nflverse.DefenseVersusPositionBuilder;
import otto.nflverse.NflverseFeedService;
import otto.records.DecisionRecord;
import otto.records.DecisionRecords;
import otto.records.ModelExchange;
import otto.records.Outcome;
import otto.records.RunKind;
import otto.research.ResearchDiscovery;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@Tag(RealModel.TAG)
class RealModelWaiverBoardTest extends WireSeamTest {

    private static final String USER_ID = "777001";
    private static final String LEAGUE_ID = "1389748403805650944";

    private static final Instant TUESDAY_EVENING = ZonedDateTime.of(2026, 9, 15, 18, 0, 0, 0,
            ZoneId.of("America/Los_Angeles")).toInstant();

    private static final String IRVING = "10001";

    /** Sleeper's {@code waiver_day_of_week} for Wednesday, the claim day the fixture covers. */
    private static final int WEDNESDAY = 2;

    private static final String REHEARSAL_BOARD = """
            {
              "hold": false,
              "opening": "Bucky Irving is the claim to make this week.",
              "picks": [{"playerId": "10001", "reasoning": "Bucky Irving played 71%% of \
            offensive snaps in his latest game, and his coach named him the starter.", \
            %s"limit": "%s"}],
              "optional": [],
              "claims": [
                {"text": "Bucky Irving played 71%% of offensive snaps", "evidence": ["usage-10001"]}
              ]
            }
            """;

    private static final String REHEARSAL_BIDDING_BOARD = REHEARSAL_BOARD.formatted(
            "\"bid\": {\"low\": 1, \"target\": 2, \"high\": 3}, ", "He is a depth add.");

    /** A rehearsal board for a league that claims by waiver priority: no bid, no limit. */
    private static final String REHEARSAL_PRIORITY_BOARD = REHEARSAL_BOARD.formatted("", "");

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

    @Test
    void aRedraftBoardClaimsTheNamedStarter() {
        tuesdayEveningBeforeWednesdayClaims("sleeper/league-in-season.json",
                REHEARSAL_BIDDING_BOARD);

        assertClaimsIrving(boardSentAfterPassingItsChecks());
    }

    @Test
    void aDynastyBoardClaimsTheNamedStarter() {
        tuesdayEveningBeforeWednesdayClaims("sleeper/league-dynasty.json",
                REHEARSAL_PRIORITY_BOARD);

        assertClaimsIrving(boardSentAfterPassingItsChecks());
    }

    @Test
    void aKeeperBoardClaimsTheNamedStarter() {
        tuesdayEveningBeforeWednesdayClaims("sleeper/league-keeper.json",
                REHEARSAL_BIDDING_BOARD);

        assertClaimsIrving(boardSentAfterPassingItsChecks());
    }

    private static String claimingOnWednesday(String league) {
        return league.replaceFirst("\"waiver_day_of_week\": \\d",
                "\"waiver_day_of_week\": " + WEDNESDAY);
    }

    private void tuesdayEveningBeforeWednesdayClaims(String league, String rehearsalBoard) {
        SleeperStubs.waiverWeek(sleeper);
        sleeper.stubFor(get(urlEqualTo(SleeperStubs.LEAGUE_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withHeader("ETag", "\"league-v1\"")
                .withBody(claimingOnWednesday(Fixtures.read(league)))));
        NflverseStubs.waiverWeekReadyForTheBoard(nflverse);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "A lineup alert.");
        checkRunner.runCheck();
        feeds.updateIfDue();
        defenseBuilder.build();
        researchDiscovery.discover();
        RealModel.answersAdvice(llm, rehearsalBoard);
        clock.set(TUESDAY_EVENING);
        checkRunner.runCheck();
    }

    private JsonNode boardSentAfterPassingItsChecks() {
        List<DecisionRecord> records = StoredRecords.of(decisionRecords,
                properties.storageDir(), USER_ID, LEAGUE_ID, RunKind.WAIVER_BOARD);
        assertThat(records).hasSize(1);
        DecisionRecord record = records.getFirst();
        ModelExchange last = record.exchanges().getLast();
        assertThat(last.checkFailures())
                .as("check failures on the model's board %s", last.response())
                .isEmpty();
        assertThat(record.outcome()).isInstanceOf(Outcome.SentBoard.class);
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", containing("Your waiver options"))));
        return last.response();
    }

    private static void assertClaimsIrving(JsonNode board) {
        assertThat(board.path("hold").asBoolean())
                .as("the board %s holds instead of claiming", board)
                .isFalse();
        assertThat(board.path("picks").findValuesAsText("playerId"))
                .as("the picks on the board %s", board)
                .contains(IRVING);
    }
}
