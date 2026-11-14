package otto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import otto.records.Outcome;
import otto.records.RunKind;
import otto.research.ResearchDiscovery;
import otto.telegram.TelegramWebhook;
import otto.telegram.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

class WaiverPreferenceScenarioTest extends WireSeamTest {

    private static final String USER_ID = "777001";
    private static final String LEAGUE_ID = "1389748403805650944";

    private static final ZoneId LOS_ANGELES = ZoneId.of("America/Los_Angeles");

    private static final Instant TUESDAY_EVENING =
            ZonedDateTime.of(2026, 9, 15, 18, 0, 0, 0, LOS_ANGELES).toInstant();
    private static final Instant AFTER_THE_CLAIMS_CLOSE =
            ZonedDateTime.of(2026, 9, 16, 8, 0, 0, 0, LOS_ANGELES).toInstant();
    private static final Instant NEXT_TUESDAY_EVENING =
            ZonedDateTime.of(2026, 9, 22, 18, 0, 0, 0, LOS_ANGELES).toInstant();

    private static final String BOARD_REQUEST = OutboundStubs.WAIVER_BOARD_REQUEST;
    private static final String PREFERENCE_TOOL = "manage_waiver_preferences";
    private static final String FOCUS_ON_TE = "{\"action\":\"set\",\"positions\":[\"TE\"]}";

    private static final String OTTON = """
            {"playerId": "10003", "reasoning": "Cade Otton is the tight end with the most \
            room to grow on this board.", "outsideFocus": "", \
            "bid": {"low": 1, "target": 2, "high": 3}, "limit": "He is a depth add."}""";

    private static final String IRVING_OVER_THE_FOCUS = """
            {"playerId": "10001", "reasoning": "Bucky Irving played 71% of offensive snaps in \
            his latest game.", "outsideFocus": "His coach named him the starter, and no tight \
            end on the board has a role that size.", \
            "bid": {"low": 1, "target": 2, "high": 3}, "limit": "He is a depth add."}""";

    private static final String IRVING_UNEXPLAINED = """
            {"playerId": "10001", "reasoning": "Bucky Irving played 71% of offensive snaps in \
            his latest game.", "outsideFocus": "", \
            "bid": {"low": 1, "target": 2, "high": 3}, "limit": "He is a depth add."}""";

    private static final String OPENING = "A pickup stands out this week.";

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
    private TelegramWebhook webhook;

    @Autowired
    private OttoProperties properties;

    private static String board(String... picks) {
        return """
                {
                  "hold": false,
                  "opening": "%s",
                  "picks": [%s],
                  "optional": [],
                  "claims": []
                }
                """.formatted(OPENING, String.join(",", picks));
    }

    private void aWaiverWeekOnDisk() {
        SleeperStubs.waiverWeek(sleeper);
        NflverseStubs.waiverWeekReadyForTheBoard(nflverse);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "A lineup alert.");
        checkRunner.runCheck();
        feeds.updateIfDue();
        defenseBuilder.build();
        researchDiscovery.discover();
    }

    private void chatRoutesTo(String tool, String arguments, String question) {
        llm.resetAll();
        telegram.resetRequests();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, tool, arguments, "Done.");
        assertThat(webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage(question)))
                .isEqualTo(WebhookResult.OK);
    }

    private void theBoardIsBuiltAt(Instant at, String... answers) {
        llm.resetAll();
        OutboundStubs.llmPhrases(llm, "Five waiver targets for Wednesday.");
        OutboundStubs.llmAdvisesInOrderOn(llm, BOARD_REQUEST, answers);
        clock.set(at);
        checkRunner.runCheck();
    }

    @Test
    void aFocusAppliesToTheNextRoundAndIsConfirmedInChat() {
        aWaiverWeekOnDisk();

        chatRoutesTo(PREFERENCE_TOOL, FOCUS_ON_TE, "focus on TE this week");

        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("focus on TE"))
                .withRequestBody(containing("Wednesday, September 16 at 3:00 AM EDT"))
                .withRequestBody(containing("ends when those claims close")));

        theBoardIsBuiltAt(TUESDAY_EVENING, board(OTTON));

        DecisionRecord record = boardRecords().getFirst();
        assertThat(record.preferences().path("waiverPreference").path("positions").toString())
                .isEqualTo("[\"TE\"]");
        assertThat(record.preferences().path("waiverPreference").path("mode").asText())
                .isEqualTo("FOCUS");
        assertThat(record.evidence()).extracting(evidence -> evidence.id())
                .contains("preference", "candidate-10001", "candidate-10003");
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(BOARD_REQUEST))
                .withRequestBody(containing("focus on TE")));
    }

    @Test
    void aClearlyBetterPickupElsewhereIsStillShownWithItsExplanation() {
        aWaiverWeekOnDisk();
        chatRoutesTo(PREFERENCE_TOOL, FOCUS_ON_TE, "focus on TE this week");

        theBoardIsBuiltAt(TUESDAY_EVENING, board(IRVING_UNEXPLAINED, OTTON),
                board(IRVING_OVER_THE_FOCUS, OTTON));

        DecisionRecord record = boardRecords().getFirst();
        assertThat(record.exchanges()).hasSize(2);
        assertThat(record.exchanges().getFirst().checkFailures())
                .anySatisfy(failure -> assertThat(failure)
                        .contains("Bucky Irving", "outside the focus"));
        assertThat(record.outcome()).isInstanceOf(Outcome.DryRun.class);
        assertThat(String.join("\n\n", ((Outcome.DryRun) record.outcome()).messages()))
                .contains("1. Bucky Irving · RB, TB\nOtto: bid $2 · Suggested range $1–$3.\n"
                        + "Bucky Irving played 71% of offensive snaps "
                        + "in his latest game.\nOutside your focus: His coach named him the "
                        + "starter, and no tight end on the board has a role that size.")
                .contains("2. Cade Otton · TE, TB");
    }

    @Test
    void aOneOffFilterShapesOnlyTheReplyAndNeverTheBoard() {
        aWaiverWeekOnDisk();

        chatRoutesTo("rank_waiver_targets", "{\"position\":\"TE\"}", "only show me TEs");

        theBoardIsBuiltAt(TUESDAY_EVENING, board(IRVING_UNEXPLAINED));

        DecisionRecord record = boardRecords().getFirst();
        assertThat(record.preferences().path("waiverPreference").isNull()).isTrue();
        assertThat(record.evidence()).extracting(evidence -> evidence.id())
                .doesNotContain("preference")
                .contains("candidate-10001");
        assertThat(record.outcome()).isInstanceOf(Outcome.DryRun.class);
    }

    @Test
    void aFilterAppliedToTheBoardKeepsItToThosePositions() {
        aWaiverWeekOnDisk();
        chatRoutesTo(PREFERENCE_TOOL, "{\"action\":\"set\",\"positions\":[\"TE\"],\"only\":true}",
                "only show TEs on my board this week");

        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("only TE")));

        theBoardIsBuiltAt(TUESDAY_EVENING, board(IRVING_OVER_THE_FOCUS), board(OTTON));

        DecisionRecord record = boardRecords().getFirst();
        assertThat(record.exchanges().getFirst().checkFailures())
                .anySatisfy(failure -> assertThat(failure).contains("10001 is not a free agent"));
        assertThat(record.candidates())
                .filteredOn(candidate -> candidate.playerId().equals("10001"))
                .singleElement()
                .satisfies(irving -> assertThat(irving.included()).isFalse())
                .satisfies(irving -> assertThat(irving.reason()).contains("only TE"));
        assertThat(record.evidence()).extracting(evidence -> evidence.id())
                .contains("candidate-10003")
                .doesNotContain("candidate-10001");
        assertThat(record.outcome()).isInstanceOf(Outcome.DryRun.class);
    }

    @Test
    void anExpiredPreferenceIsNeverRevivedFromTheConversation() {
        aWaiverWeekOnDisk();
        chatRoutesTo(PREFERENCE_TOOL, FOCUS_ON_TE, "focus on TE this week");
        theBoardIsBuiltAt(TUESDAY_EVENING, board(OTTON));

        clock.set(AFTER_THE_CLAIMS_CLOSE);
        chatRoutesTo(PREFERENCE_TOOL, "{\"action\":\"show\"}", "am I still focused on TE?");

        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("focus on TE this week"))
                .withRequestBody(containing("no waiver preference is in force")));

        theBoardIsBuiltAt(NEXT_TUESDAY_EVENING, board(IRVING_UNEXPLAINED));

        List<DecisionRecord> records = boardRecords();
        assertThat(records).hasSize(2);
        DecisionRecord nextRound = records.getLast();
        assertThat(nextRound.preferences().path("waiverPreference").isNull()).isTrue();
        assertThat(nextRound.evidence()).extracting(evidence -> evidence.id())
                .doesNotContain("preference");
        assertThat(nextRound.outcome()).isInstanceOf(Outcome.DryRun.class);
        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(BOARD_REQUEST))
                .withRequestBody(notContaining("focus on TE")));
    }

    @Test
    void aPreferenceForAnUnknownPositionIsRefused() {
        aWaiverWeekOnDisk();

        chatRoutesTo(PREFERENCE_TOOL, "{\"action\":\"set\",\"positions\":[\"K\"]}",
                "focus on kickers this week");

        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("K is not a position the waiver board ranks")));
        theBoardIsBuiltAt(TUESDAY_EVENING, board(OTTON));
        assertThat(boardRecords().getFirst().preferences().path("waiverPreference").isNull())
                .isTrue();
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
