package otto;

import java.util.List;

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
import otto.telegram.TelegramWebhook;
import otto.telegram.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A player's ceiling and floor by chat, checked before the user reads it.
 *
 * <p>The fixture week is 2026 week 2 in a full-PPR league that pays half
 * a point more per tight-end catch. Josh Jacobs projects 16.5 points, and
 * his weekly scores are modelled as a gamma distribution around that
 * projection. SciPy puts its 10th, 25th, 75th and 90th percentiles at
 * 7.1, 10.4, 21.1 and 27.7 points, and its chance of reaching the RB12
 * line of 17.4 points at 39%. His one 2026 game scored 18 points, a boom
 * week, which the answer may mention as history only.
 */
class CeilingScenarioTest extends WireSeamTest {

    private static final String USER_ID = "777001";
    private static final String LEAGUE_ID = "1389748403805650944";

    private static final String JACOBS = "{\"player\":\"Josh Jacobs\"}";

    private static final String CHECKED = "Josh Jacobs usually lands between 10 and 21 "
            + "points, a bad week drops to about 7, and a big week reaches 28 or more. He has "
            + "about a 39% chance of an RB12 week, which is 17.4 points in your league. As "
            + "history, he cleared that line in 1 of his 1 games this season.";

    private static final String WRONG = "Josh Jacobs has about a 70% chance of an RB12 week "
            + "and a big week reaches 28 or more.";

    private static final String TOOL_RESULT = "\"role\":\"tool\"";

    private static final String NARRATION = "Here is what Jacobs can do.";

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

    private void aWeekOfDataOnDisk() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH,
                "sleeper/projections-edge.json", "projections-v1");
        NflverseStubs.healthy(nflverse);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "An alert about the bench edge.");

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

    private static String quoted(String text) {
        return OutboundStubs.jsonString(text);
    }

    private static String checkedAnswer(String message) {
        return """
                {"message": %s, "move": null, "players": ["5850"], "claims": []}
                """.formatted(quoted(message));
    }

    private static void toolResultCarries(String text) {
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("get_player_ceiling"))
                .withRequestBody(containing(text)));
    }

    private static void theUserReadsTheCheckedTextAndNoSecondReply() {
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing(quoted(CHECKED))));
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing(NARRATION)));
        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(TOOL_RESULT)));
    }

    private List<DecisionRecord> ceilingRecords() {
        return StoredRecords.of(decisionRecords, properties.storageDir(), USER_ID, LEAGUE_ID,
                RunKind.PLAYER_CEILING);
    }

    @Test
    void aCeilingQuestionGetsTheBoomChanceAndTheFloorInPlainWords() {
        aWeekOfDataOnDisk();
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_player_ceiling", JACOBS, NARRATION);
        OutboundStubs.llmAdvises(llm, checkedAnswer(CHECKED));

        ask("what's Josh Jacobs' ceiling?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("legalMoves"))
                .withRequestBody(containing("16.5"))
                .withRequestBody(containing("RB12"))
                .withRequestBody(containing("17.4"))
                .withRequestBody(containing("39%"))
                .withRequestBody(containing("10 to 21"))
                .withRequestBody(containing("28")));
        theUserReadsTheCheckedTextAndNoSecondReply();
        assertThat(ceilingRecords()).singleElement().satisfies(record ->
                assertThat(record.outcome()).isEqualTo(new Outcome.Sent(CHECKED)));
    }

    @Test
    void pastBoomWeeksAreLabelledAsHistory() {
        aWeekOfDataOnDisk();
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_player_ceiling", JACOBS, NARRATION);
        OutboundStubs.llmAdvises(llm, checkedAnswer(CHECKED));

        ask("is Jacobs boom or bust?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("legalMoves"))
                .withRequestBody(containing("history only"))
                .withRequestBody(containing("39%")));
    }

    @Test
    void withoutAnyGameHistoryTheEstimateIsTheSame() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH,
                "sleeper/projections-edge.json", "projections-v1");
        NflverseStubs.healthy(nflverse);
        nflverse.stubFor(get(urlEqualTo(NflverseStubs.STATS_RELEASE_PATH))
                .willReturn(aResponse().withStatus(503)));
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "An alert.");
        checkRunner.runCheck();
        feeds.updateIfDue();
        llm.resetAll();
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_player_ceiling", JACOBS, NARRATION);
        OutboundStubs.llmAdvises(llm,
                checkedAnswer("Josh Jacobs has about a 39% chance of an RB12 week."));

        ask("is Jacobs boom or bust?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("legalMoves"))
                .withRequestBody(containing("39%"))
                .withRequestBody(notContaining("history only")));
    }

    @Test
    void theTightEndLineIncludesTheLeaguesTightEndPremium() {
        aWeekOfDataOnDisk();
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_player_ceiling",
                "{\"player\":\"Travis Kelce\"}", NARRATION);
        OutboundStubs.llmAdvises(llm,
                "{\"message\": \"Travis Kelce.\", \"players\": [\"1466\"], \"claims\": []}");

        ask("what's Kelce's ceiling?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("legalMoves"))
                // 14.6 in full PPR, plus half a point on each of a TE6
                // week's 5.3 catches.
                .withRequestBody(containing("TE6"))
                .withRequestBody(containing("17.2")));
    }

    @Test
    void aWrongBoomChanceIsCorrectedBeforeTheUserSeesIt() {
        aWeekOfDataOnDisk();
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_player_ceiling", JACOBS, NARRATION);
        OutboundStubs.llmAdvises(llm, checkedAnswer(WRONG), checkedAnswer(CHECKED));

        ask("what's Jacobs' ceiling?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("number 70 is not in the evidence")));
        theUserReadsTheCheckedTextAndNoSecondReply();
        assertThat(ceilingRecords()).singleElement().satisfies(record ->
                assertThat(record.outcome()).isEqualTo(new Outcome.Sent(CHECKED)));
    }

    @Test
    void aBoomChanceThatStaysWrongIsWithheld() {
        aWeekOfDataOnDisk();
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_player_ceiling", JACOBS, NARRATION);
        OutboundStubs.llmAdvises(llm, checkedAnswer(WRONG));

        ask("what's Jacobs' ceiling?");

        toolResultCarries("could not check");
        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("get_player_ceiling"))
                .withRequestBody(notContaining("legalMoves"))
                .withRequestBody(containing("70%")));
        assertThat(ceilingRecords()).singleElement().satisfies(record ->
                assertThat(record.outcome()).isInstanceOf(Outcome.Withheld.class));
    }

    @Test
    void aCeilingAskedBesideAnotherToolIsExplainedByTheChatModel() {
        aWeekOfDataOnDisk();
        OutboundStubs.llmCallsToolsAtOnceThenPhrases(llm, NARRATION,
                "get_player_ceiling", JACOBS, "get_standings", "{}");
        OutboundStubs.llmAdvises(llm, checkedAnswer(CHECKED));

        ask("what's Jacobs' ceiling, and where do I stand?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(TOOL_RESULT))
                .withRequestBody(containing(CHECKED.substring(0, 40))));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing(NARRATION)));
        assertThat(ceilingRecords()).singleElement().satisfies(record ->
                assertThat(record.outcome()).isEqualTo(new Outcome.HandedToChat(CHECKED)));
    }

    @Test
    void anOrdinaryToolResultIsStillExplainedByTheChatModel() {
        aWeekOfDataOnDisk();
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_standings", "{}", NARRATION);

        ask("where do I stand?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(TOOL_RESULT))
                .withRequestBody(containing("STANDINGS")));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing(NARRATION)));
    }
}
