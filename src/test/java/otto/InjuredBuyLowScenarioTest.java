package otto;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import otto.research.ResearchCache;
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
 * Injured buy-low targets judged by the league's format, in trade offers
 * and in the answer to "should I buy him while he's hurt?".
 *
 * <p>The league is in week 12 of a regular season that ends in week 14,
 * and its six playoff teams play weeks 15 to 17. The user is 8-3.
 * EndZoneEnvy is 2-9 and holds two Atlanta running backs on injured
 * reserve with knee injuries, each projected for 28.0 points a game. News
 * from week 10 says RB Depth 01 misses 2-3 weeks, so he is back in week
 * 13 and plays 5 games through week 17. RB Depth 02 is out for the
 * season.
 */
class InjuredBuyLowScenarioTest extends WireSeamTest {

    private static final String USER_ID = "777001";
    private static final String LEAGUE_ID = "1389748403805650944";

    private static final String RETURNING = "92001";
    private static final String SEASON_OVER = "92002";

    private static final String NARRATION = "Here is what I think.";

    private static final String RETURNING_ANSWER = "RB Depth 01 is worth buying: in a redraft "
            + "league he is worth about 140.0 points over the 5 games he plays from week 13 "
            + "through week 17. RotoWire reported on November 10 that he is expected to miss "
            + "2-3 weeks. You're 8-3 and can wait for him, and EndZoneEnvy is 2-9 and needs "
            + "points now.";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private TelegramWebhook webhook;

    @Autowired
    private DecisionRecords decisionRecords;

    @Autowired
    private ResearchCache research;

    @Autowired
    private OttoProperties properties;

    private void leagueInWeek12(String leagueFixture) {
        clock.set(java.time.Instant.parse("2026-11-24T18:00:00Z"));
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-team-injured.json", "players-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH, leagueFixture, "league-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-offers-injured.json", "rosters-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.USERS_PATH,
                "sleeper/users-league.json", "users-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.STATE_PATH,
                "sleeper/state-nfl-week12.json", "state-v1");
        for (int remaining = 12; remaining <= 14; remaining++) {
            SleeperStubs.stubJson(sleeper, "/v1/projections/nfl/regular/2026/" + remaining,
                    "sleeper/projections-trade-injured.json", "projections-w" + remaining);
            SleeperStubs.stubJson(sleeper, "/scores/nfl/regular/2026/" + remaining,
                    "sleeper/scores-trade-w" + remaining + ".json", "scores-w" + remaining);
        }
        for (int played = 1; played <= 12; played++) {
            SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH + "/transactions/" + played,
                    played == 5 ? "sleeper/transactions-offers-trade.json"
                            : "sleeper/transactions-none.json", "transactions-w" + played);
        }
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH + "/traded_picks",
                "sleeper/traded-picks-trade.json", "traded-picks-v1");
        SleeperStubs.noNewsByDefault(sleeper);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "A quiet week.");
        checkRunner.runCheck();
        llm.resetAll();
        telegram.resetRequests();
        OutboundStubs.telegramOk(telegram);
    }

    private void newsIsRead(String playerId, String fixture) {
        SleeperStubs.stubNews(sleeper, playerId, fixture);
        research.refresh(playerId);
    }

    private void redraftLeague() {
        leagueInWeek12("sleeper/league-in-season.json");
        fantasyCalcAnswers("false", "fantasycalc/values-offers-redraft-injured.json");
        newsIsRead(RETURNING, "sleeper/news-92001-weeks.json");
        newsIsRead(SEASON_OVER, "sleeper/news-92002-season.json");
    }

    private static void fantasyCalcAnswers(String dynasty, String fixture) {
        fantasyCalc.stubFor(get(urlPathEqualTo("/values/current"))
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

    private Map<String, String> judge(String player, String reply) {
        OutboundStubs.llmCallsToolThenPhrases(llm, "judge_injured_player",
                "{\"player\":\"" + player + "\"}", NARRATION);
        OutboundStubs.llmAdvises(llm, reply);
        ask("should I buy " + player + " while he's hurt?");
        return evidence(RunKind.INJURED_BUY_LOW).values().stream()
                .filter(facts -> facts.containsKey("worth"))
                .findFirst()
                .orElseThrow();
    }

    private static void theUserReads(String message) {
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing(OutboundStubs.jsonString(message))));
    }

    @Test
    void aRedraftPlayerBackMidSeasonIsWorthOnlyHisGamesBeforeThePlayoffsEnd() {
        redraftLeague();

        Map<String, String> injury = judge("RB Depth 01", answer(RETURNING_ANSWER, RETURNING));

        assertThat(injury)
                .containsEntry("status", "he is on injured reserve")
                .containsEntry("injury", "knee")
                .containsEntry("rosteredBy", "EndZoneEnvy")
                .containsEntry("timeline",
                        "the news gives a timeline: \"RB Depth 01 expected to miss 2-3 weeks\"")
                .containsEntry("reportedBy", "rotowire")
                .containsEntry("reported", "November 10")
                .containsEntry("likelyBack",
                        "week 13, planning on the long end of what the news says")
                .containsEntry("gamesBack", "5 games from his return through week 17, when "
                        + "your league's playoffs end")
                .containsEntry("pointsWhenHePlays",
                        "28.0 points a game when he plays, in your league's scoring")
                .containsEntry("worth", "in a redraft league he is worth only the games he "
                        + "plays after he's back: about 140.0 points")
                .containsEntry("worthBuying", "yes")
                .containsEntry("timing", "you're 8-3 and can wait for him, and EndZoneEnvy is "
                        + "2-9 and needs points now");
        theUserReads(RETURNING_ANSWER);
        assertThat(records(RunKind.INJURED_BUY_LOW)).singleElement().satisfies(record ->
                assertThat(record.outcome()).isEqualTo(new Outcome.Sent(RETURNING_ANSWER)));
    }

    @Test
    void aRedraftPlayerWhoseSeasonIsOverIsWorthNothingAndInNoOffer() {
        redraftLeague();

        Map<String, String> injury = judge("RB Depth 02", answer("RB Depth 02 is worth "
                + "nothing to you: his season is over.", SEASON_OVER));

        assertThat(injury)
                .containsEntry("timeline", "the news says his injury ends his season: "
                        + "\"RB Depth 02 out for the season\"")
                .containsEntry("worth",
                        "in a redraft league he is worth 0 to you, because his season is over")
                .containsEntry("worthBuying", "no")
                .doesNotContainKeys("gamesBack", "timing");

        llm.resetAll();
        telegram.resetRequests();
        OutboundStubs.telegramOk(telegram);
        clock.advance(java.time.Duration.ofSeconds(1));
        OutboundStubs.llmCallsToolThenPhrases(llm, "suggest_trades", "{\"count\":5}", NARRATION);
        OutboundStubs.llmAdvises(llm, answer("Here are your offers."));
        ask("what trades should I make?");

        Map<String, Map<String, String>> offers = evidence(RunKind.TRADE_IDEAS);
        assertThat(offers).doesNotContainKey("injury-" + SEASON_OVER);
        assertThat(offers.values()).noneSatisfy(facts -> assertThat(facts.get("youGet"))
                .contains("RB Depth 02"));
    }

    @Test
    void anInjuredPlayerBackForTheStretchIsTargetedInANamedOfferWithTheTimingPitch() {
        redraftLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "suggest_trades", "{}", NARRATION);
        OutboundStubs.llmAdvises(llm, answer("Here are your offers."));

        ask("what trades should I make?");

        Map<String, Map<String, String>> offers = evidence(RunKind.TRADE_IDEAS);
        assertThat(offers.values()).anySatisfy(facts -> assertThat(facts)
                .containsEntry("sendTo", "EndZoneEnvy")
                .containsEntry("youGet", "RB Depth 01"));
        assertThat(offers.get("injury-" + RETURNING))
                .containsEntry("worthBuying", "yes")
                .containsEntry("timing", "you're 8-3 and can wait for him, and EndZoneEnvy is "
                        + "2-9 and needs points now");
    }

    @Test
    void evaluateTradePricesAnInjuredPlayerFromHisReturnAsTheOffersDo() {
        redraftLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                "{\"manager\":\"EndZoneEnvy\",\"gets\":\"RB Depth 01, RB Depth 02\","
                        + "\"gives\":\"Jalen Reagor\"}", "Judged.");

        ask("should I trade Reagor for RB Depth 01 and RB Depth 02?");

        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("RB Depth 01 is priced from his likely return in "
                        + "week 13, 2 of the games left"))
                .withRequestBody(containing("The news says RB Depth 02's injury ends his "
                        + "season, so he is priced at nothing for the weeks left")));
    }

    @Test
    void aKeeperIsWorthHisRedraftValuePlusHisKeeperValue() {
        leagueInWeek12("sleeper/league-keeper.json");
        fantasyCalcAnswers("false", "fantasycalc/values-offers-redraft-injured.json");
        fantasyCalcAnswers("true", "fantasycalc/values-offers-dynasty-injured.json");
        newsIsRead(SEASON_OVER, "sleeper/news-92002-season.json");

        Map<String, String> injury = judge("RB Depth 02", answer("He is worth keeping.",
                SEASON_OVER));

        assertThat(injury).containsEntry("worth", "in a keeper league he is worth his redraft "
                + "value, which is 0 because his season is over, plus his keeper value of "
                + "2600, so 2600 in all");
        assertThat(injury).doesNotContainKey("timing");
    }

    @Test
    void aDynastyPlayerIsWorthHisLongTermValueEvenWhenHisSeasonIsOver() {
        leagueInWeek12("sleeper/league-dynasty.json");
        fantasyCalcAnswers("true", "fantasycalc/values-offers-dynasty-injured.json");
        newsIsRead(SEASON_OVER, "sleeper/news-92002-season.json");

        Map<String, String> injury = judge("RB Depth 02", answer("He is worth 5800.",
                SEASON_OVER));

        assertThat(injury)
                .containsEntry("worth", "in a dynasty league he is worth his long-term value, "
                        + "5800 on the dynasty market")
                .containsEntry("worthBuying", "yes");
    }

    @Test
    void newsWithNoTimetableIsSaidInThoseWords() {
        leagueInWeek12("sleeper/league-in-season.json");
        fantasyCalcAnswers("false", "fantasycalc/values-offers-redraft-injured.json");
        newsIsRead(RETURNING, "sleeper/news-92001-no-timetable.json");

        Map<String, String> injury = judge("RB Depth 01", answer("There is no timetable for "
                + "return for RB Depth 01.", RETURNING));

        assertThat(injury)
                .containsEntry("timeline", "no timetable for return")
                .containsEntry("worthBuying", "no")
                .doesNotContainKeys("likelyBack", "gamesBack");
    }

    @Test
    void newsWithoutATimelineMeansOttoSaysItDoesNotKnowIt() {
        leagueInWeek12("sleeper/league-in-season.json");
        fantasyCalcAnswers("false", "fantasycalc/values-offers-redraft-injured.json");
        newsIsRead(RETURNING, "sleeper/news-92001-silent.json");

        Map<String, String> injury = judge("RB Depth 01", answer("I don't know his timeline "
                + "for his knee.", RETURNING));

        assertThat(injury)
                .containsEntry("timeline", "I don't know his timeline for his knee")
                .doesNotContainKeys("reportedBy", "likelyBack");
    }

    @Test
    void newsThatOnlySaysHeCouldReturnInAWeekIsNoTimeline() {
        leagueInWeek12("sleeper/league-in-season.json");
        fantasyCalcAnswers("false", "fantasycalc/values-offers-redraft-injured.json");
        newsIsRead(RETURNING, "sleeper/news-92001-hedged-week.json");

        Map<String, String> injury = judge("RB Depth 01", answer("I don't know his timeline "
                + "for his knee.", RETURNING));

        assertThat(injury)
                .containsEntry("timeline", "I don't know his timeline for his knee")
                .doesNotContainKeys("reportedBy", "likelyBack");
    }

    @Test
    void aReplyThatInventsAReturnWeekIsCorrectedOnceAndThenWithheld() {
        leagueInWeek12("sleeper/league-in-season.json");
        fantasyCalcAnswers("false", "fantasycalc/values-offers-redraft-injured.json");
        newsIsRead(RETURNING, "sleeper/news-92001-silent.json");
        String invented = answer("RB Depth 01 should be back in week 15.", RETURNING);
        OutboundStubs.llmCallsToolThenPhrases(llm, "judge_injured_player",
                "{\"player\":\"RB Depth 01\"}", NARRATION);
        OutboundStubs.llmAdvises(llm, invented, invented);

        ask("should I buy RB Depth 01 while he's hurt?");

        assertThat(records(RunKind.INJURED_BUY_LOW)).singleElement().satisfies(record -> {
            assertThat(record.exchanges()).hasSize(2);
            assertThat(record.outcome()).isInstanceOf(Outcome.Withheld.class);
        });
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("back in week 15")));
    }
}
