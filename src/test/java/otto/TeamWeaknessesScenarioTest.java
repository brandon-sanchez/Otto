package otto;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.harness.Fixtures;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.StoredRecords;
import otto.harness.WireSeamTest;
import otto.records.DecisionRecord;
import otto.records.DecisionRecords;
import otto.records.Outcome;
import otto.records.RunKind;
import otto.storage.OttoJson;
import otto.telegram.TelegramWebhook;
import otto.telegram.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * "What are my team's weaknesses?" by chat: one checked message built
 * from a profile of every team in the league.
 *
 * <p>The league is in week 12 of a regular season that ends in week 14,
 * and every player projects the same points in each of the three weeks
 * left, so his per-game average is that number. Four teams hold players.
 * The user starts McCaffrey (16.0) and a 6.0-point RB beside him, with no
 * RB behind them: Josh Jacobs sits on IR, ruled out, so he cannot be
 * activated and does not count. That puts both his RB starters (11.0
 * against 15.0 to 16.5) and his RB depth (0.0) 4th of 4. He holds six
 * receivers from 12.0 to 19.0 points. Twelve WRs start across the four
 * best lineups and the twelfth-best rostered WR averages 12.0, so all six
 * of his are startable for the four spots that start a WR outside
 * Superflex: a surplus. GridironGoblin mirrors him, deep at RB and thin
 * at WR. The best free agent RB averages 13.0.
 */
class TeamWeaknessesScenarioTest extends WireSeamTest {

    private static final Instant WEEK_12 = Instant.parse("2026-11-24T18:00:00Z");

    private static final String USER_ID = "777001";
    private static final String LEAGUE_ID = "1389748403805650944";
    private static final String VALUES_PATH = "/values/current";

    private static final String NARRATION = "Here is how your team looks.";

    private static final String CHECKED = """
            Your team is strong at QB and TE, but running back is thin and you have more \
            receivers than you can start.

            At RB your starters rank 4th of 4 and your depth ranks 4th of 4. Josh Jacobs is \
            on IR and can't be activated now, so he isn't counted. At WR you have 6 \
            startable WRs for 4 spots.

            Your lineup's big weeks rank 1st of 4, and Justin Jefferson has the best chance             of a boom week among your starters at 41%.

            Claim RB Depth 11 off waivers. He averages 13.0 points a game and would be 2nd \
            among your RBs. You could also trade Tyler Lockett or Jalen Reagor to \
            GridironGoblin, who is deeper than you at RB and thin at WR.

            Free data carries no injury return dates, so I can't say when Josh Jacobs will \
            be back.""";

    private static final String WRONG_RANK = "Your RB depth ranks 9th of 12, so claim RB "
            + "Depth 11 off waivers.";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private TelegramWebhook webhook;

    @Autowired
    private DecisionRecords decisionRecords;

    @Autowired
    private OttoProperties properties;

    private void leagueInWeek(String leagueFixture, String stateFixture, int week) {
        clock.set(WEEK_12);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-team.json", "players-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH, leagueFixture, "league-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-team.json", "rosters-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.USERS_PATH,
                "sleeper/users-league.json", "users-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.STATE_PATH, stateFixture, "state-v1");
        for (int remaining = week; remaining <= 14; remaining++) {
            SleeperStubs.stubJson(sleeper, "/v1/projections/nfl/regular/2026/" + remaining,
                    "sleeper/projections-team.json", "projections-w" + remaining);
            SleeperStubs.stubJson(sleeper, "/scores/nfl/regular/2026/" + remaining,
                    "sleeper/scores-trade-w" + remaining + ".json", "scores-w" + remaining);
        }
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH + "/transactions/" + week,
                "sleeper/transactions-none.json", "transactions-w" + week);
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH + "/transactions/" + (week - 1),
                "sleeper/transactions-none.json", "transactions-w" + (week - 1));
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "A quiet week.");
        checkRunner.runCheck();
        llm.resetAll();
        telegram.resetRequests();
        OutboundStubs.telegramOk(telegram);
    }

    private void redraftLeague() {
        leagueInWeek("sleeper/league-in-season.json", "sleeper/state-nfl-week12.json", 12);
        fantasyCalcAnswers("false", "fantasycalc/values-redraft.json");
    }

    private static void fantasyCalcAnswers(String dynasty, String fixture) {
        fantasyCalc.stubFor(get(urlPathEqualTo(VALUES_PATH))
                .withQueryParam("isDynasty", equalTo(dynasty))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(Fixtures.read(fixture))));
    }

    private void ask(String text) {
        assertThat(webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage(text)))
                .isEqualTo(WebhookResult.OK);
    }

    private static String answer(String message, String... players) {
        return """
                {"message": %s, "move": null, "players": [%s], "claims": [], "comparisons": []}
                """.formatted(OutboundStubs.jsonString(message), String.join(", ",
                List.of(players).stream().map(id -> "\"" + id + "\"").toList()));
    }

    private static String checkedAnswer() {
        return answer(CHECKED, "5850", "6794", "92011", "4029", "6768");
    }

    private List<DecisionRecord> weaknessRecords() {
        return StoredRecords.of(decisionRecords, properties.storageDir(), USER_ID, LEAGUE_ID,
                RunKind.TEAM_WEAKNESSES);
    }

    private Map<String, Map<String, String>> evidence() {
        DecisionRecord record = weaknessRecords().getLast();
        JsonNode messages = record.exchanges().getFirst().request().path("messages");
        try {
            JsonNode bundle = OttoJson.MAPPER.readTree(messages.get(1).path("content").asText());
            Map<String, Map<String, String>> items = new HashMap<>();
            bundle.path("evidence").forEach(item -> {
                Map<String, String> facts = new HashMap<>();
                item.path("facts").properties()
                        .forEach(fact -> facts.put(fact.getKey(), fact.getValue().asText()));
                items.put(item.path("id").asText(), facts);
            });
            return items;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void theUserReads(String message) {
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing(OutboundStubs.jsonString(message))));
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing(NARRATION)));
    }

    @Test
    void aThinPositionIsRankedWithItsDepthAndAnIrPlayerWhoCannotBeActivatedIsLeftOut() {
        redraftLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_team_weaknesses", "{}", NARRATION);
        OutboundStubs.llmAdvises(llm, checkedAnswer());

        ask("what are my team's weaknesses?");

        Map<String, Map<String, String>> evidence = evidence();
        assertThat(evidence.get("position-RB"))
                .containsEntry("strength", "thin")
                .containsEntry("starters", "11.0 points a game from your top 2 RBs")
                .containsEntry("starterRank", "4th of 4")
                .containsEntry("depth", "0.0 points a game from your next 2 RBs")
                .containsEntry("depthRank", "4th of 4");
        assertThat(evidence).doesNotContainKey("player-5850");
        assertThat(evidence.get("sidelined-5850").get("counted"))
                .contains("cannot be activated now");
        assertThat(evidence.get("suggestion-1"))
                .containsEntry("move", "claim RB Depth 11 off waivers")
                .containsEntry("average", "13.0 points a game over the rest of the season")
                .containsEntry("rank", "2nd among your RBs if you add him");
        assertThat(evidence.get("limits").get("limit1"))
                .isEqualTo("Free data carries no injury return dates, so I can't say when "
                        + "Josh Jacobs will be back");
        theUserReads(CHECKED);
        assertThat(weaknessRecords()).singleElement().satisfies(record ->
                assertThat(record.outcome()).isEqualTo(new Outcome.Sent(CHECKED)));
    }

    @Test
    void aSurplusPositionCountsStartablePlayersAgainstTheSpotsThatCanStartThem() {
        redraftLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_team_weaknesses", "{}", NARRATION);
        OutboundStubs.llmAdvises(llm, checkedAnswer());

        ask("how can I improve my team?");

        Map<String, Map<String, String>> evidence = evidence();
        assertThat(evidence.get("position-WR"))
                .containsEntry("strength", "in surplus")
                .containsEntry("startable", "6 startable WRs for 4 spots that can start one, "
                        + "leaving Superflex to quarterbacks")
                .containsEntry("starterRank", "1st of 4")
                .containsEntry("marketShape", "by FantasyCalc position rank: 2 WR1, 2 WR2, "
                        + "0 WR3, 0 below WR3, 2 not on the list");
        assertThat(evidence.get("suggestion-2"))
                .containsEntry("move", "trade from your WR surplus for help at RB")
                .containsEntry("give", "Tyler Lockett or Jalen Reagor, beyond the 4 WR spots")
                .containsEntry("partners", "GridironGoblin");
        assertThat(evidence.get("limits").get("limit2")).contains("route participation");
        assertThat(evidence.get("ceiling"))
                .containsEntry("boomStarters",
                        "2.1 of your 9 starters reach a boom week in an average week")
                .containsEntry("boomRank", "1st of 4")
                .containsEntry("bestBoom", "Justin Jefferson has the best chance of a boom "
                        + "week among your starters, 41%");
    }

    @Test
    void aDynastyLeagueReadsTheDynastyMarket() {
        leagueInWeek("sleeper/league-dynasty.json", "sleeper/state-nfl-week12.json", 12);
        fantasyCalcAnswers("true", "fantasycalc/values-dynasty.json");
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_team_weaknesses", "{}", NARRATION);
        OutboundStubs.llmAdvises(llm, checkedAnswer());

        ask("what are my team's weaknesses?");

        Map<String, Map<String, String>> evidence = evidence();
        assertThat(evidence.get("position-WR").get("marketShape"))
                .isEqualTo("by FantasyCalc position rank: 2 WR1, 0 WR2, 0 WR3, 1 below WR3, "
                        + "3 not on the list");
        assertThat(evidence.get("market-2216"))
                .containsEntry("rank", "41st among WRs on FantasyCalc")
                .containsEntry("list", "FantasyCalc, 12-team Superflex PPR dynasty");
    }

    @Test
    void aWrongRankIsCorrectedBeforeTheUserSeesIt() {
        redraftLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_team_weaknesses", "{}", NARRATION);
        OutboundStubs.llmAdvises(llm, answer(WRONG_RANK, "92011"), checkedAnswer());

        ask("what are my team's weaknesses?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("rank 9th is not in the evidence")));
        theUserReads(CHECKED);
    }

    @Test
    void aRankThatStaysWrongIsWithheld() {
        redraftLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_team_weaknesses", "{}", NARRATION);
        OutboundStubs.llmAdvises(llm, answer(WRONG_RANK, "92011"));

        ask("what are my team's weaknesses?");

        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("9th of 12")));
        assertThat(weaknessRecords()).singleElement().satisfies(record ->
                assertThat(record.outcome()).isInstanceOf(Outcome.Withheld.class));
    }

    @Test
    void askingAboutEveryLeagueAnswersTheOneOttoFollowsAndSaysSo() {
        redraftLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_team_weaknesses",
                "{\"league\":\"all of them\"}", NARRATION);
        OutboundStubs.llmAdvises(llm, checkedAnswer());

        ask("what are the weaknesses on all my teams?");

        assertThat(evidence().get("leagues")).containsEntry("leagues",
                "I follow one league, Raising Canes Demon, so this answer covers that league "
                        + "only");
        theUserReads(CHECKED);
    }

    @Test
    void aLeagueOttoDoesNotFollowIsNamedBackRatherThanGuessed() {
        redraftLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_team_weaknesses",
                "{\"league\":\"office league\"}", NARRATION);

        ask("what are my weaknesses in my office league?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("I follow one league, Raising Canes Demon")));
        assertThat(weaknessRecords()).isEmpty();
    }

    @Test
    void afterTheTradeDeadlineOnlyWaiverMovesAreSuggested() {
        leagueInWeek("sleeper/league-in-season.json", "sleeper/state-nfl-week14.json", 14);
        fantasyCalcAnswers("false", "fantasycalc/values-redraft.json");
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_team_weaknesses", "{}", NARRATION);
        OutboundStubs.llmAdvises(llm, checkedAnswer());

        ask("what are my team's weaknesses?");

        Map<String, Map<String, String>> evidence = evidence();
        assertThat(evidence.get("trade-deadline").get("tradeDeadline"))
                .startsWith("the trade deadline passed after week 13");
        assertThat(evidence.get("suggestion-1")).containsEntry("move",
                "claim RB Depth 11 off waivers");
        assertThat(evidence).doesNotContainKey("suggestion-2");
    }

    @Test
    void aCheckNeverSendsTheTeamReadUnasked() {
        redraftLeague();
        OutboundStubs.llmPhrases(llm, "A quiet week.");

        checkRunner.runCheck();

        assertThat(weaknessRecords()).isEmpty();
        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("strong, thin or in surplus")));
    }
}
