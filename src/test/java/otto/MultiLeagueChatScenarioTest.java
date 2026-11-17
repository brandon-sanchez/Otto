package otto;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.followed.FollowedLeague;
import otto.followed.FollowedLeagues;
import otto.harness.Fixtures;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.TwoLeagues;
import otto.harness.WireSeamTest;
import otto.storage.OttoJson;
import otto.telegram.TelegramWebhook;
import otto.telegram.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chat with two leagues followed: a redraft league and a dynasty league.
 * Each answer is about the league the conversation names, Otto asks
 * which one when nothing says, "all of them" answers each league in
 * turn, and the owner can leave a league out and bring it back.
 */
class MultiLeagueChatScenarioTest extends WireSeamTest {

    private static final Instant WEEK_12 = Instant.parse("2026-11-24T18:00:00Z");

    private static final String DYNASTY_PATH = "/v1/league/" + TwoLeagues.DYNASTY_LEAGUE;

    private static final String REDRAFT_READ = "Your running backs are thin.";
    private static final String DYNASTY_READ = "Your quarterbacks carry this team.";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private TelegramWebhook webhook;

    @Autowired
    private FollowedLeagues leagues;

    /** Both leagues in week 12 with the team-analysis rosters, after one Check. */
    private void twoLeaguesInWeek12() {
        clock.set(WEEK_12);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-team.json", "players-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.STATE_PATH, "sleeper/state-nfl-week12.json",
                "state-v1");
        SleeperStubs.stubJson(sleeper, TwoLeagues.USER_LEAGUES_PATH,
                "sleeper/user-leagues-two.json", "user-leagues-v1");
        league(SleeperStubs.LEAGUE_PATH, "sleeper/league-in-season.json", "redraft");
        league(DYNASTY_PATH, "sleeper/league-second-dynasty.json", "dynasty");
        for (int week = 12; week <= 14; week++) {
            SleeperStubs.stubJson(sleeper, "/v1/projections/nfl/regular/2026/" + week,
                    "sleeper/projections-team.json", "projections-w" + week);
            SleeperStubs.stubJson(sleeper, "/scores/nfl/regular/2026/" + week,
                    "sleeper/scores-trade-w" + week + ".json", "scores-w" + week);
        }
        fantasyCalcAnswers("false", "fantasycalc/values-redraft.json");
        fantasyCalcAnswers("true", "fantasycalc/values-dynasty.json");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "A quiet week.");
        checkRunner.runCheck();
        llm.resetAll();
        telegram.resetRequests();
        OutboundStubs.telegramOk(telegram);
    }

    private static void league(String path, String fixture, String tag) {
        SleeperStubs.stubJson(sleeper, path, fixture, tag + "-league");
        SleeperStubs.stubJson(sleeper, path + "/rosters", "sleeper/rosters-team.json",
                tag + "-rosters");
        SleeperStubs.stubJson(sleeper, path + "/users", "sleeper/users-league.json",
                tag + "-users");
        for (int week = 11; week <= 12; week++) {
            SleeperStubs.stubJson(sleeper, path + "/transactions/" + week,
                    "sleeper/transactions-none.json", tag + "-transactions-w" + week);
        }
    }

    private static void fantasyCalcAnswers(String dynasty, String fixture) {
        fantasyCalc.stubFor(get(urlPathEqualTo("/values/current"))
                .withQueryParam("isDynasty", equalTo(dynasty))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(Fixtures.read(fixture))));
    }

    /**
     * The chat model asks for the weaknesses read, and the advice model
     * writes each league's read from that league's evidence. The stubs
     * match on what each request carries, not on their order, because
     * the leagues are answered at the same time.
     */
    private static void modelReadsEachLeague() {
        llm.stubFor(post(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .atPriority(10)
                .willReturn(completion(OutboundStubs.toolCallBody("get_team_weaknesses",
                        "{}"))));
        adviceFor(TwoLeagues.CONFIG_LEAGUE_NAME, REDRAFT_READ);
        adviceFor(TwoLeagues.DYNASTY_LEAGUE_NAME, DYNASTY_READ);
    }

    private static void adviceFor(String leagueName, String read) {
        llm.stubFor(post(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .atPriority(1)
                .withRequestBody(containing(leagueName))
                .willReturn(completion(OutboundStubs.contentBody("""
                        {"message": %s, "move": null, "players": [], "claims": [],
                         "comparisons": []}""".formatted(OutboundStubs.jsonString(read))))));
    }

    private static ResponseDefinitionBuilder completion(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(body);
    }

    private void say(String text) {
        assertThat(webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage(text)))
                .isEqualTo(WebhookResult.OK);
    }

    private static List<String> sent() {
        return telegram.findAll(postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)))
                .stream()
                .map(LoggedRequest::getBodyAsString)
                .map(MultiLeagueChatScenarioTest::text)
                .toList();
    }

    private static String text(String body) {
        try {
            JsonNode json = OttoJson.MAPPER.readTree(body);
            return json.path("text").asText();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void allOfThemAnswersEachLeagueInTurnEachNamingItsLeague() {
        twoLeaguesInWeek12();
        modelReadsEachLeague();

        say("what are the weaknesses on all of them?");

        assertThat(sent()).containsExactly(
                "League: " + TwoLeagues.CONFIG_LEAGUE_NAME + "\n\n" + REDRAFT_READ,
                "League: " + TwoLeagues.DYNASTY_LEAGUE_NAME + "\n\n" + DYNASTY_READ);
    }

    @Test
    void aQuestionThatNamesNoLeagueIsAskedAboutAndTheShortAnswerPicksTheLeague() {
        twoLeaguesInWeek12();
        OutboundStubs.llmPhrases(llm, "Start your best lineup.");

        say("who should I start this week?");
        assertThat(sent()).containsExactly("Which league is that about? "
                + "Raising Canes Demon, Dynasty Bros, or all of them?");

        say("the dynasty one");
        assertThat(sent()).last().isEqualTo(
                "League: Dynasty Bros\n\nStart your best lineup.");
        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("who should I start this week?")));

        say("and who is on my bench?");
        assertThat(sent()).last().isEqualTo(
                "League: Dynasty Bros\n\nStart your best lineup.");
    }

    @Test
    void aQuestionThatNamesALeagueIsAnsweredThereWithoutAsking() {
        twoLeaguesInWeek12();
        OutboundStubs.llmPhrases(llm, "Start your best lineup.");

        say("who should I start in Raising Canes Demon?");

        assertThat(sent()).containsExactly(
                "League: Raising Canes Demon\n\nStart your best lineup.");
    }

    @Test
    void theOwnerLeavesALeagueOutInChatAndBringsItBack() {
        twoLeaguesInWeek12();

        say("leave out Dynasty Bros");
        assertThat(leagues.followed()).extracting(FollowedLeague::leagueId)
                .containsExactly(TwoLeagues.CONFIG_LEAGUE);
        say("/leagues");
        say("bring back the dynasty league");

        assertThat(sent()).containsExactly(
                "Done, I'll leave Dynasty Bros out: no checks, alerts or answers for it until "
                        + "you say \"bring back Dynasty Bros\".",
                """
                        I follow 1 league:
                        - Raising Canes Demon (redraft)

                        Left out:
                        - Dynasty Bros (dynasty)

                        Say "leave out" or "bring back" with a league's name to change this.""",
                "Dynasty Bros is back. I'll check it every minute again.");
        assertThat(leagues.followed()).extracting(FollowedLeague::leagueId)
                .containsExactly(TwoLeagues.CONFIG_LEAGUE, TwoLeagues.DYNASTY_LEAGUE);
        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH)));
    }

    @Test
    void withOneLeagueLeftAQuestionIsAnsweredThereWithoutAskingOrNamingIt() {
        twoLeaguesInWeek12();
        say("leave out Dynasty Bros");
        OutboundStubs.llmPhrases(llm, "Start your best lineup.");

        say("who should I start this week?");

        assertThat(sent()).last().isEqualTo("Start your best lineup.");
    }
}
