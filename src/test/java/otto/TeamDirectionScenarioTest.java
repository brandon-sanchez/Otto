package otto;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
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
import otto.webhook.TelegramWebhook;
import otto.webhook.WebhookResult;

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
 * "Who's contending in my league?" by chat, and the user's own label in
 * the weaknesses answer.
 *
 * <p>The league is in week 12 of a regular season that ends in week 14,
 * eleven games played. Four teams hold players. GridironGoblin is 9-2
 * with 1400 points, EndZoneEnvy 6-5 with 1250, WaiverWizard 5-6 with
 * 1100 and the user 2-9 with 950. On the market EndZoneEnvy's roster is
 * worth 10000, WaiverWizard's 9000, GridironGoblin's 6000 and the user's
 * 1000, his two priced players being 31.2 and 27.4 years old. The
 * eight other teams are all 6-5, and 6 of the 12 make the playoffs. With
 * most of the season played, the playoff race and points weigh a little
 * more than the market: GridironGoblin and EndZoneEnvy are contending, WaiverWizard
 * is in the middle, and the user's numbers say rebuilding.
 */
class TeamDirectionScenarioTest extends WireSeamTest {

    private static final Instant WEEK_12 = Instant.parse("2026-11-24T18:00:00Z");

    private static final String USER_ID = "777001";
    private static final String LEAGUE_ID = "1389748403805650944";
    private static final String VALUES_PATH = "/values/current";

    private static final String NARRATION = "Here is where every team stands.";

    private static final String CHECKED = """
            GridironGoblin and EndZoneEnvy are contending. GridironGoblin is 9-2 with the \
            most points, and EndZoneEnvy has the most valuable roster at 6-5. WaiverWizard \
            is in the middle at 5-6, and at 2-9 with the fewest points your team looks like \
            it's rebuilding.""";

    private static final String WRONG_RECORD = "GridironGoblin is 10-1 and contending.";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private TelegramWebhook webhook;

    @Autowired
    private DecisionRecords decisionRecords;

    @Autowired
    private OttoProperties properties;

    private void leagueInWeek12(String leagueFixture) {
        leagueInWeek12(leagueFixture, "sleeper/rosters-direction.json");
    }

    private void leagueInWeek12(String leagueFixture, String rostersFixture) {
        clock.set(WEEK_12);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-team.json", "players-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH, leagueFixture,
                "league-" + leagueFixture);
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH, rostersFixture,
                "rosters-" + rostersFixture);
        SleeperStubs.stubJson(sleeper, SleeperStubs.USERS_PATH,
                "sleeper/users-league.json", "users-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.STATE_PATH,
                "sleeper/state-nfl-week12.json", "state-v1");
        for (int remaining = 12; remaining <= 14; remaining++) {
            SleeperStubs.stubJson(sleeper, "/v1/projections/nfl/regular/2026/" + remaining,
                    "sleeper/projections-team.json", "projections-w" + remaining);
            SleeperStubs.stubJson(sleeper, "/scores/nfl/regular/2026/" + remaining,
                    "sleeper/scores-trade-w" + remaining + ".json", "scores-w" + remaining);
        }
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH + "/transactions/12",
                "sleeper/transactions-none.json", "transactions-w12");
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH + "/transactions/11",
                "sleeper/transactions-none.json", "transactions-w11");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "A quiet week.");
        checkRunner.runCheck();
        nextQuestion();
    }

    private void redraftLeague() {
        leagueInWeek12("sleeper/league-in-season.json");
        fantasyCalcAnswers("false", "fantasycalc/values-direction-redraft.json");
    }

    private void dynastyLeague() {
        leagueInWeek12("sleeper/league-dynasty.json");
        fantasyCalcAnswers("true", "fantasycalc/values-direction-dynasty.json");
    }

    private static void nextQuestion() {
        llm.resetAll();
        telegram.resetRequests();
        OutboundStubs.telegramOk(telegram);
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

    private void tellOtto(String text, String arguments) {
        OutboundStubs.llmCallsToolThenPhrases(llm, "manage_team_direction", arguments,
                "Got it.");
        ask(text);
        nextQuestion();
    }

    private static String answer(String message) {
        return """
                {"message": %s, "move": null, "players": [], "claims": [], "comparisons": []}
                """.formatted(OutboundStubs.jsonString(message));
    }

    private List<DecisionRecord> records(RunKind kind) {
        return StoredRecords.of(decisionRecords, properties.storageDir(), USER_ID, LEAGUE_ID,
                kind);
    }

    private Map<String, Map<String, String>> evidence(RunKind kind) {
        DecisionRecord record = records(kind).getLast();
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

    private void askWhoIsContending() {
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_team_directions", "{}", NARRATION);
        OutboundStubs.llmAdvises(llm, answer(CHECKED));
        ask("who's contending in my league?");
    }

    private void askForWeaknesses() {
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_team_weaknesses", "{}", NARRATION);
        OutboundStubs.llmAdvises(llm, answer("Your team is thin."));
        ask("what are my team's weaknesses?");
    }

    @Test
    void everyTeamInARedraftLeagueGetsALabelAndAOneLineReason() {
        redraftLeague();

        askWhoIsContending();

        Map<String, Map<String, String>> evidence = evidence(RunKind.TEAM_DIRECTIONS);
        assertThat(evidence.get("team-2"))
                .containsEntry("manager", "GridironGoblin")
                .containsEntry("direction", "contending")
                .containsEntry("reason", "9-2, 3 games clear of the first team out of the 6 playoff spots, "
                        + "with the most points and the 3rd-most valuable "
                        + "roster");
        assertThat(evidence.get("team-4"))
                .containsEntry("direction", "contending")
                .containsEntry("reason", "6-5, level on wins with the first team out of the 6 "
                        + "playoff spots, with the 2nd-most points and the most valuable "
                        + "roster");
        assertThat(evidence.get("team-3"))
                .containsEntry("direction", "in the middle")
                .containsEntry("reason", "5-6, a game out of the last of the 6 playoff spots, "
                        + "with the 3rd-most points and the 2nd-most "
                        + "valuable roster");
        assertThat(evidence.get("team-1"))
                .containsEntry("manager", "you")
                .containsEntry("direction", "rebuilding")
                .containsEntry("reason", "2-9, 4 games out of the last of the 6 playoff spots, "
                        + "with the fewest points and the least valuable "
                        + "roster")
                .containsEntry("marketValue",
                        "1000 in market value for a 12-team Superflex PPR redraft league")
                .doesNotContainKey("question");
        theUserReads(CHECKED);
        assertThat(records(RunKind.TEAM_DIRECTIONS)).singleElement().satisfies(record ->
                assertThat(record.outcome()).isEqualTo(new Outcome.Sent(CHECKED)));
    }

    @Test
    void aRankTheReasonWordsPassesTheFactCheck() {
        redraftLeague();
        String ranked = "EndZoneEnvy is contending at 6-5 with the 2nd-most points, and "
                + "WaiverWizard is in the middle at 5-6 with the 3rd-most points.";
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_team_directions", "{}", NARRATION);
        OutboundStubs.llmAdvises(llm, answer(ranked));

        ask("who's contending in my league?");

        assertThat(evidence(RunKind.TEAM_DIRECTIONS).get("team-4"))
                .containsEntry("pointsRank", "2nd of 4 in points scored, 1st being the most")
                .containsEntry("rosterValueRank",
                        "1st of 4 in roster market value, 1st being the most valuable");
        theUserReads(ranked);
    }

    @Test
    void aWrongRecordIsCorrectedBeforeTheUserSeesIt() {
        redraftLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_team_directions", "{}", NARRATION);
        OutboundStubs.llmAdvises(llm, answer(WRONG_RECORD), answer(CHECKED));

        ask("who's contending in my league?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("10 is not in the evidence")));
        theUserReads(CHECKED);
    }

    @Test
    void aDynastyLeagueAsksBeforeTreatingTheUserAsRebuilding() {
        dynastyLeague();

        askWhoIsContending();

        Map<String, Map<String, String>> evidence = evidence(RunKind.TEAM_DIRECTIONS);
        assertThat(evidence.get("team-1"))
                .containsEntry("direction", "in the middle until you tell me otherwise")
                .containsEntry("measured", "rebuilding")
                .containsEntry("question", "Your numbers look like a rebuilding team. Are "
                        + "you rebuilding? I won't treat you that way until you say so.")
                .containsEntry("reason", "2-9, 4 games out of the last of the 6 playoff spots, "
                        + "with the fewest points and the least valuable "
                        + "roster, and its most valuable players average 29.3 years old")
                .containsEntry("marketValue",
                        "1000 in market value for a 12-team Superflex PPR dynasty league");
        assertThat(evidence.get("team-3").get("reason"))
                .endsWith("and its most valuable players average 23.9 years old");
    }

    /**
     * Every team holds its own 2027 1st (3000) and 2nd (1000), and the
     * user also holds GridironGoblin's 1st, so the user's 1000 in players
     * is 8000 with his picks and GridironGoblin's picks add only 1000.
     */
    @Test
    void aDynastyTeamsValueCountsTheDraftPicksItHolds() {
        leagueInWeek12("sleeper/league-dynasty.json");
        fantasyCalcAnswers("true", "fantasycalc/values-direction-dynasty-picks.json");
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH + "/traded_picks",
                "sleeper/traded-picks-direction.json", "traded-picks-v1");

        askWhoIsContending();

        assertThat(evidence(RunKind.TEAM_DIRECTIONS).get("team-1")).containsEntry(
                "marketValue", "8000 in market value for a 12-team Superflex PPR dynasty league");
    }

    /**
     * The league's rookie drafts run three rounds by Sleeper's
     * {@code draft_rounds}, so the user's own 2027 3rd (500) counts too.
     */
    @Test
    void aDynastyTeamsPicksCountInEveryRoundItsRookieDraftsRun() {
        leagueInWeek12("sleeper/league-dynasty.json");
        fantasyCalcAnswers("true", "fantasycalc/values-direction-dynasty-picks-3rd.json");
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH + "/traded_picks",
                "sleeper/traded-picks-direction.json", "traded-picks-v1");

        askWhoIsContending();

        assertThat(evidence(RunKind.TEAM_DIRECTIONS).get("team-1")).containsEntry(
                "marketValue", "8500 in market value for a 12-team Superflex PPR dynasty league");
    }

    @Test
    void sayingYesInADynastyLeagueMakesTheUserRebuilding() {
        dynastyLeague();
        tellOtto("yes, we're rebuilding", "{\"action\":\"set\",\"direction\":\"rebuilding\"}");

        askWhoIsContending();

        assertThat(evidence(RunKind.TEAM_DIRECTIONS).get("team-1"))
                .containsEntry("direction", "rebuilding, as you told me")
                .doesNotContainKey("question");
    }

    @Test
    void theUsersOwnLabelSticksAndShowsInTheWeaknessesAnswer() {
        redraftLeague();
        tellOtto("we're contending this year", "{\"action\":\"set\","
                + "\"direction\":\"contending\"}");

        askForWeaknesses();

        assertThat(evidence(RunKind.TEAM_WEAKNESSES).get("direction"))
                .containsEntry("direction", "contending, as you told me")
                .containsEntry("measured", "rebuilding")
                .containsEntry("reason", "2-9, 4 games out of the last of the 6 playoff spots, "
                        + "with the fewest points and the least valuable "
                        + "roster");

        nextQuestion();
        clock.advance(Duration.ofSeconds(1));
        tellOtto("forget what I said about contending", "{\"action\":\"clear\"}");
        askForWeaknesses();

        assertThat(evidence(RunKind.TEAM_WEAKNESSES).get("direction"))
                .containsEntry("direction", "rebuilding");
    }

    @Test
    void theWeaknessesAnswerShowsTheMeasuredLabel() {
        redraftLeague();

        askForWeaknesses();

        assertThat(evidence(RunKind.TEAM_WEAKNESSES).get("direction"))
                .containsEntry("direction", "rebuilding")
                .containsEntry("reason", "2-9, 4 games out of the last of the 6 playoff spots, "
                        + "with the fewest points and the least valuable "
                        + "roster");
    }

    @Test
    void anUnknownDirectionIsNamedBackRatherThanStored() {
        redraftLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "manage_team_direction",
                "{\"action\":\"set\",\"direction\":\"tanking\"}", "Got it.");

        ask("we're tanking");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("t a direction I use; say contending")));
        nextQuestion();
        askWhoIsContending();
        assertThat(evidence(RunKind.TEAM_DIRECTIONS).get("team-1"))
                .containsEntry("direction", "rebuilding");
    }

    /**
     * WaiverWizard is 5-5 with four games left, and no market list can be
     * read, so his label rests on where he stands and his points, 3rd of
     * the four teams with players. When 6 of the 12 teams make the
     * playoffs he is a game out of the last spot and in the middle.
     */
    @Test
    void fiveAndFiveAGameOutOfSixPlayoffSpotsIsInTheMiddle() {
        leagueInWeek12("sleeper/league-in-season.json", "sleeper/rosters-direction-five-five.json");

        askWhoIsContending();

        assertThat(evidence(RunKind.TEAM_DIRECTIONS).get("team-3"))
                .containsEntry("direction", "in the middle")
                .containsEntry("reason", "5-5, a game out of the last of the 6 playoff spots, "
                        + "with the 3rd-most points");
    }

    /** The same 5-5 team when only 4 teams make the playoffs is two games out and rebuilding. */
    @Test
    void fiveAndFiveTwoGamesOutOfFourPlayoffSpotsIsRebuilding() {
        leagueInWeek12("sleeper/league-in-season-four-playoff-teams.json",
                "sleeper/rosters-direction-five-five.json");

        askWhoIsContending();

        assertThat(evidence(RunKind.TEAM_DIRECTIONS).get("team-3"))
                .containsEntry("direction", "rebuilding")
                .containsEntry("reason", "5-5, 2 games out of the last of the 4 playoff spots, "
                        + "with the 3rd-most points");
    }

    /** Moves on to a later question, so its Decision Record is the newest one. */
    private void later() {
        nextQuestion();
        clock.advance(Duration.ofMinutes(10));
    }

    /** The league's rosters change, and a Check reads them before the next question. */
    private void rostersBecome(String rostersFixture) {
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH, rostersFixture,
                "rosters-" + rostersFixture);
        clock.advance(Duration.ofMinutes(10));
        OutboundStubs.llmPhrases(llm, "A quiet week.");
        checkRunner.runCheck();
        later();
    }

    @Test
    void theDynastyRebuildingQuestionIsAskedOnceASeason() {
        dynastyLeague();
        askWhoIsContending();
        assertThat(evidence(RunKind.TEAM_DIRECTIONS).get("team-1")).containsKey("question");

        later();
        askWhoIsContending();

        assertThat(evidence(RunKind.TEAM_DIRECTIONS).get("team-1"))
                .containsEntry("direction", "in the middle until you tell me otherwise")
                .containsEntry("measured", "rebuilding")
                .doesNotContainKey("question");

        later();
        askForWeaknesses();

        assertThat(evidence(RunKind.TEAM_WEAKNESSES).get("direction"))
                .containsEntry("direction", "in the middle until you tell me otherwise")
                .doesNotContainKey("question");
    }

    /**
     * After the question goes unanswered, the user's team swings two
     * tiers to contending, then falls back to rebuilding: that is a big
     * enough change to ask again.
     */
    @Test
    void aSwingToContendingAndBackAsksTheRebuildingQuestionAgain() {
        dynastyLeague();
        askWhoIsContending();
        later();

        rostersBecome("sleeper/rosters-direction-user-on-top.json");
        askWhoIsContending();
        assertThat(evidence(RunKind.TEAM_DIRECTIONS).get("team-1"))
                .containsEntry("direction", "contending")
                .doesNotContainKey("question");

        rostersBecome("sleeper/rosters-direction.json");
        askWhoIsContending();

        assertThat(evidence(RunKind.TEAM_DIRECTIONS).get("team-1"))
                .containsEntry("measured", "rebuilding")
                .containsKey("question");
    }
}
