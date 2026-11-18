package otto;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.TwoLeagues;
import otto.harness.WireSeamTest;
import otto.nflverse.NflverseFeedService;
import otto.storage.OttoJson;
import otto.webhook.TelegramWebhook;
import otto.webhook.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chat with two leagues followed and a question that names neither.
 * Otto answers at once when the answer is the same in every league, and
 * asks which league only when the answer depends on one.
 */
class LeagueNeutralQuestionsScenarioTest extends WireSeamTest {

    private static final String WHICH_LEAGUE =
            "Which league is that about? Raising Canes Demon, Dynasty Bros, or all of them?";

    private static final String LIKELY_ID = "8150";

    private static final String SNAPS = "Isaiah Likely played 52 snaps in week 5, 78% of "
            + "the Ravens' offensive snaps, and drew 7 of their 31 targets, a 23% share.";

    private static final String POINTS =
            "Isaiah Likely has <b>46.0</b> points over 4 games (11.5 per game).";

    private static final String SNAP_QUESTION =
            "What was Isaiah Likely's snap percentage this last game?";

    private static final String SNAP_CALL =
            "{\"player\":\"Isaiah Likely\",\"weeksAgo\":1,\"asked\":\"snap percentage\"}";

    private static final String NARRATION = "Here are his numbers.";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private TelegramWebhook webhook;

    @BeforeEach
    void twoLeaguesAndTheRavensSeason() {
        TwoLeagues.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-likely.json", "players-v1");
        SleeperStubs.likelysPlayedWeeks(sleeper);
        NflverseStubs.isaiahLikelysSeason(nflverse);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "An alert.");

        checkRunner.runCheck();
        feeds.updateIfDue();

        freshModelAndChat();
    }

    private static void freshModelAndChat() {
        llm.resetAll();
        telegram.resetRequests();
        OutboundStubs.telegramOk(telegram);
    }

    private void say(String text) {
        assertThat(webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage(text)))
                .isEqualTo(WebhookResult.OK);
    }

    private static void modelCalls(String tool, String arguments) {
        OutboundStubs.llmCallsToolThenPhrases(llm, tool, arguments, NARRATION);
    }

    private static void statsWriteUp(String message) {
        OutboundStubs.llmAdvises(llm, """
                {"message": %s, "move": null, "players": ["%s"], "claims": []}
                """.formatted(OutboundStubs.jsonString(message), LIKELY_ID));
    }

    private static List<String> sent() {
        return telegram.findAll(postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)))
                .stream()
                .map(LoggedRequest::getBodyAsString)
                .map(LeagueNeutralQuestionsScenarioTest::text)
                .toList();
    }

    private static String text(String body) {
        try {
            return OttoJson.MAPPER.readTree(body).path("text").asText();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void aPlayersSnapPercentageIsAnsweredWithoutAskingWhichLeagueOrNamingOne() {
        modelCalls("get_player_stats", SNAP_CALL);
        statsWriteUp(SNAPS);

        say(SNAP_QUESTION);

        assertThat(sent()).containsExactly(SNAPS);
    }

    @Test
    void statLeadersAreListedWithoutAskingWhichLeague() {
        modelCalls("get_stat_leaders", "{\"stat\":\"air_yards\",\"position\":\"TE\"}");

        say("top 10 TEs in air yards");

        assertThat(sent()).singleElement().satisfies(leaders -> assertThat(leaders)
                .contains("tight ends")
                .contains("air yards")
                .contains("Isaiah Likely"));
    }

    @Test
    void fantasyPointsDependOnTheLeagueSoOttoAsksAndTheShortReplyAnswers() {
        modelCalls("get_player_stats",
                "{\"player\":\"Isaiah Likely\",\"asked\":\"fantasy points\","
                        + "\"fantasyPoints\":true}");
        statsWriteUp(POINTS);

        say("How many fantasy points has Isaiah Likely scored this season?");
        assertThat(sent()).containsExactly(WHICH_LEAGUE);

        llm.resetScenarios();
        say("the dynasty one");
        assertThat(sent()).last().isEqualTo("🏈 <b>Dynasty Bros</b>\n\n" + POINTS);
        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("legalMoves"))
                .withRequestBody(containing("Dynasty Bros"))
                .withRequestBody(containing("full PPR, tight ends +0.5 per catch")));
    }

    @Test
    void aLineupQuestionStillAsksWhichLeagueBeforeAnyToolRuns() {
        modelCalls("recommend_lineup", "{}");

        say("who should I start?");

        assertThat(sent()).containsExactly(WHICH_LEAGUE);
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH)));
    }

    @Test
    void aNeutralAnswerCarriesNoLeagueHeadingWhileTheChatIsAboutALeague() {
        OutboundStubs.llmPhrases(llm, "Start your best lineup.");
        say("who should I start in Dynasty Bros?");
        assertThat(sent()).containsExactly("🏈 <b>Dynasty Bros</b>\n\nStart your best lineup.");

        freshModelAndChat();
        modelCalls("get_player_stats", SNAP_CALL);
        statsWriteUp(SNAPS);
        say(SNAP_QUESTION);

        assertThat(sent()).containsExactly(SNAPS);
    }

    @Test
    void aQuestionWaitingOnTheLeagueKeepsWaitingThroughANeutralAnswer() {
        modelCalls("recommend_lineup", "{}");
        say("who should I start?");

        freshModelAndChat();
        modelCalls("get_player_stats", SNAP_CALL);
        statsWriteUp(SNAPS);
        say(SNAP_QUESTION);
        assertThat(sent()).containsExactly(SNAPS);

        freshModelAndChat();
        OutboundStubs.llmPhrases(llm, "Start your best lineup.");
        say("the dynasty one");

        assertThat(sent()).containsExactly("🏈 <b>Dynasty Bros</b>\n\nStart your best lineup.");
        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("who should I start?")));
    }

    @Test
    void aFollowUpThatCallsNoToolCarriesOnFromTheNeutralAnswerBeforeIt() {
        OutboundStubs.llmPhrases(llm, "Start your best lineup.");
        say("who should I start in Dynasty Bros?");
        freshModelAndChat();
        modelCalls("get_player_stats", SNAP_CALL);
        statsWriteUp(SNAPS);
        say(SNAP_QUESTION);

        freshModelAndChat();
        OutboundStubs.llmPhrases(llm, "He ran a route on most dropbacks.");
        say("why?");

        assertThat(sent()).containsExactly("He ran a route on most dropbacks.");
    }

    @Test
    void aNeutralQuestionAskedAboutEveryLeagueIsAnsweredOnce() {
        modelCalls("get_player_stats", SNAP_CALL);
        statsWriteUp(SNAPS);

        say("What was Isaiah Likely's snap percentage last game, for all my leagues?");

        assertThat(sent()).containsExactly(SNAPS);
    }
}
