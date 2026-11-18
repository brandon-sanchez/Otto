package otto;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
import otto.webhook.TelegramWebhook;
import otto.webhook.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

class PlayerStatsScenarioTest extends WireSeamTest {

    private static final String USER_ID = "777001";
    private static final String LEAGUE_ID = "1389748403805650944";
    private static final String TOOL = "get_player_stats";
    private static final String LIKELY_ID = "8150";

    private static final String LAST_WEEK = "Isaiah Likely played 52 snaps in week 5, 78% of "
            + "the Ravens' offensive snaps, and drew 7 of their 31 targets, a 23% share. For the "
            + "season his 66% average snap share ranks 2nd of 3 tight ends with at least 2 games.";

    private static final String WEEK_BEFORE = "In week 4 Isaiah Likely played 45 snaps, 66% of "
            + "the Ravens' offensive snaps, and drew 6 of their 34 targets, an 18% share.";

    private static final String ADVANCED = "Yes, plenty. This season Isaiah Likely has drawn 10 "
            + "first-read targets, 5 red-zone targets and 3 end-zone targets on 191 air yards. "
            + "Next Gen Stats has him at 3.1 yards of separation on 22 targets, and he's scored "
            + "46.9 PPR points against 37.8 expected.";

    private static final String RANKS = "Isaiah Likely's season ranks among TEs (weeks 1–5)\n"
            + "🟡 Snap share: <b>2nd</b> of 3 (66%)";

    private static final String RAMBLING = (LAST_WEEK + " ").repeat(3).strip();

    private static final String NARRATION = "Here are his numbers.";

    private static final String TOOL_RESULT = "\"role\":\"tool\"";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private TelegramWebhook webhook;

    @Autowired
    private DecisionRecords decisionRecords;

    @Autowired
    private OttoProperties properties;

    @BeforeEach
    void ravensSeasonOnDisk() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-likely.json", "players-v1");
        NflverseStubs.isaiahLikelysSeason(nflverse);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "An alert.");

        checkRunner.runCheck();
        feeds.updateIfDue();

        llm.resetAll();
        telegram.resetRequests();
        OutboundStubs.telegramOk(telegram);
    }

    private void ask(String text) {
        assertThat(webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage(text)))
                .isEqualTo(WebhookResult.OK);
    }

    private void askAndAnswer(String text, String arguments, String checked) {
        llm.resetAll();
        telegram.resetRequests();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, TOOL, arguments, NARRATION);
        OutboundStubs.llmAdvises(llm, answer(checked));
        ask(text);
    }

    private static String answer(String message) {
        return """
                {"message": %s, "move": null, "players": ["%s"], "claims": []}
                """.formatted(OutboundStubs.jsonString(message), LIKELY_ID);
    }

    /** Returns one evidence fact as the request carries it: JSON inside a JSON string. */
    private static String fact(String key, String value) {
        return factStarting(key, value) + "\\\"";
    }

    private static String factStarting(String key, String value) {
        return "\\\"%s\\\":\\\"%s".formatted(key, value);
    }

    private static void theEvidenceHolds(String... facts) {
        var request = postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("legalMoves"));
        for (String fact : facts) {
            request = request.withRequestBody(containing(fact));
        }
        llm.verify(1, request);
    }

    private static void theUserReadsOnly(String checked) {
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing(OutboundStubs.jsonString(checked))));
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing(NARRATION)));
        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(TOOL_RESULT)));
    }

    private List<DecisionRecord> statsRecords() {
        return StoredRecords.of(decisionRecords, properties.storageDir(), USER_ID, LEAGUE_ID,
                RunKind.PLAYER_STATS);
    }

    @Test
    void theOwnersConversationGetsTheRealNumbersEachTime() {
        askAndAnswer("What was snap percentage last week for Isaiah Likely",
                "{\"player\":\"Isaiah Likely\",\"weeksAgo\":1,\"asked\":\"snap percentage\"}",
                LAST_WEEK);

        theEvidenceHolds("snap percentage", "week 5 of 2026", "52", "78%", "7 of BAL's 31",
                "23%", fact("rank", "2nd"), fact("population", "of 3 tight ends"));
        theUserReadsOnly(LAST_WEEK);

        askAndAnswer("What about the week before?",
                "{\"player\":\"Isaiah Likely\",\"week\":4,\"asked\":\"snap percentage\"}",
                WEEK_BEFORE);

        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(TOOL))
                .withRequestBody(containing("52 snaps in week 5")));
        theEvidenceHolds("week 4 of 2026", "45", "66%", "6 of BAL's 34", "18%");
        theUserReadsOnly(WEEK_BEFORE);

        askAndAnswer("Can you see any advanced stats?",
                "{\"player\":\"Isaiah Likely\",\"asked\":\"advanced stats\"}", ADVANCED);

        theEvidenceHolds("weeks 1 to 5 of 2026", fact("firstReadTargets", "10"),
                fact("redZoneTargets", "5"), fact("endZoneTargets", "3"), 
                fact("airYards", "191"),
                fact("averageSeparationYards", "3.1"), "22 targets", "46.9", "37.8",
                "route participation");
        theUserReadsOnly(ADVANCED);

        assertThat(statsRecords()).extracting(DecisionRecord::outcome).containsExactly(
                new Outcome.Sent(LAST_WEEK), new Outcome.Sent(WEEK_BEFORE),
                new Outcome.Sent(ADVANCED));
    }

    @Test
    void aPlayerNextGenNeverListedIsNamedAsUnknownNotZero() {
        OutboundStubs.llmCallsToolThenPhrases(llm, TOOL,
                "{\"player\":\"Mark Andrews\",\"asked\":\"advanced stats\"}", NARRATION);
        OutboundStubs.llmAdvises(llm, """
                {"message": "Mark Andrews.", "move": null, "players": ["5012"], "claims": []}
                """);

        ask("what advanced stats can you see for Mark Andrews?");

        theEvidenceHolds("Next Gen Stats lists only players who clear the NFL's qualifying "
                + "minimum", "did not list Mark Andrews");
        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("legalMoves"))
                .withRequestBody(containing("averageSeparationYards")));
    }

    @Test
    void aSourceThatNeverHeldHisGamesIsUnknownNeverZero() {
        OutboundStubs.llmCallsToolThenPhrases(llm, TOOL,
                "{\"player\":\"Travis Kelce\",\"weeksAgo\":1}", NARRATION);
        OutboundStubs.llmAdvises(llm, """
                {"message": "Travis Kelce.", "move": null, "players": ["1466"], "claims": []}
                """);

        ask("what were Kelce's red zone looks last week?");

        theEvidenceHolds("week 5 of 2026", factStarting("redZoneTargets", "unknown"),
                factStarting("firstReadTargets", "unknown"),
                factStarting("expectedFantasyPoints", "unknown"));
        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("legalMoves"))
                .withRequestBody(containing(fact("redZoneTargets", "0"))));
    }

    @Test
    void aRunningBacksLastGamesCarryHisPfrContactFiguresAndRanks() {
        OutboundStubs.llmCallsToolThenPhrases(llm, TOOL,
                "{\"player\":\"Derrick Henry\",\"lastGames\":2}", NARRATION);
        OutboundStubs.llmAdvises(llm, """
                {"message": "Derrick Henry.", "move": null, "players": ["3198"], "claims": []}
                """);

        ask("how has Derrick Henry been running lately?");

        theEvidenceHolds("weeks 4 to 5 of 2026, BAL's last 2 games", "40 of BAL's 56",
                fact("yardsBeforeContactPerCarry", "2.0"), fact("brokenTackles", "5"),
                "40 carries", "yards before contact per carry, season to date",
                "carry share, season to date", "did not list Derrick Henry for week 4");
    }

    @Test
    void aWeekWithNoGameSaysSoInsteadOfShowingZeros() {
        OutboundStubs.llmCallsToolThenPhrases(llm, TOOL,
                "{\"player\":\"Isaiah Likely\",\"week\":3}", NARRATION);

        ask("what was Likely's snap share in week 3?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(TOOL_RESULT))
                .withRequestBody(containing("BAL had a bye in week 3")));
        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("legalMoves")));
    }

    @Test
    void twoSpansAtOnceAreSentBackRatherThanGuessed() {
        OutboundStubs.llmCallsToolThenPhrases(llm, TOOL,
                "{\"player\":\"Isaiah Likely\",\"week\":4,\"lastGames\":3}", NARRATION);

        ask("Likely's snaps?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(TOOL_RESULT))
                .withRequestBody(containing("give one of week, weeksAgo or lastGames")));
        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("legalMoves")));
    }

    @Test
    void aWeekNotPlayedYetIsNotAnswered() {
        OutboundStubs.llmCallsToolThenPhrases(llm, TOOL,
                "{\"player\":\"Isaiah Likely\",\"week\":6}", NARRATION);

        ask("Likely's snaps in week 6?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(TOOL_RESULT))
                .withRequestBody(containing("game in week 6 is not finished")));
    }

    @Test
    void aRanksQuestionLeadsWithTheRankLinesCodeWrote() {
        askAndAnswer("where does Likely rank in snap share?",
                "{\"player\":\"Isaiah Likely\",\"asked\":\"snap share rank\"}", RANKS);

        theEvidenceHolds(fact("header", "Isaiah Likely's season ranks among TEs (weeks 1–5)"),
                "Snap share: <b>2nd</b> of 3 (66%)");
        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("When he asks for ranks, the first line is")));
        theUserReadsOnly(RANKS);
    }

    @Test
    void aLongStatAnswerIsSentBackToBeShort() {
        OutboundStubs.llmCallsToolThenPhrases(llm, TOOL,
                "{\"player\":\"Isaiah Likely\",\"weeksAgo\":1}", NARRATION);
        OutboundStubs.llmAdvises(llm, answer(RAMBLING), answer(LAST_WEEK));

        ask("Likely's snap share last week?");

        theEvidenceHolds("the limit is 600");
        theUserReadsOnly(LAST_WEEK);
        assertThat(statsRecords()).singleElement().satisfies(record ->
                assertThat(record.outcome()).isEqualTo(new Outcome.Sent(LAST_WEEK)));
    }

    @Test
    void aStatAskedBesideAnotherToolIsExplainedByTheChatModel() {
        OutboundStubs.llmCallsToolsAtOnceThenPhrases(llm, NARRATION,
                TOOL, "{\"player\":\"Isaiah Likely\",\"weeksAgo\":1}", "get_standings", "{}");
        OutboundStubs.llmAdvises(llm, answer(LAST_WEEK));

        ask("Likely's snaps last week, and where do I stand?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(TOOL_RESULT))
                .withRequestBody(notContaining("legalMoves"))
                .withRequestBody(containing("52 snaps in week 5")));
        assertThat(statsRecords()).singleElement().satisfies(record ->
                assertThat(record.outcome()).isEqualTo(new Outcome.HandedToChat(LAST_WEEK)));
    }
}
