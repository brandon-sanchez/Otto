package otto;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.telegram.TelegramWebhook;
import otto.telegram.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trade evaluation: the user Asks whether a trade is worth taking and
 * gets both sides priced over the rest of the season, in league
 * scoring, from both teams' points of view, with a verdict, a
 * confidence level and a leverage note.
 *
 * <p>The scenario runs the league in week 12 of a regular season that
 * ends in week 14, so the rest of the season is three weeks and a
 * player is worth three weeks of projection. The projection fixture
 * prices every rostered player at a round number, so each verdict band
 * is forced by the fixture rather than by the assertion.
 *
 * <p>The user holds a deep set of running backs and receivers and a
 * thin pair of tight ends. GridironGoblin is the mirror of that: the
 * best tight end in the league and nothing much behind his starting
 * running backs.
 */
class TradeScenarioTest extends WireSeamTest {

    /** The Tuesday of week 12, before any game of that week locks. */
    private static final Instant WEEK_12 = Instant.parse("2026-11-24T18:00:00Z");

    private static final String PROJECTIONS_12 = "/v1/projections/nfl/regular/2026/12";
    private static final String PROJECTIONS_13 = "/v1/projections/nfl/regular/2026/13";
    private static final String PROJECTIONS_14 = "/v1/projections/nfl/regular/2026/14";
    private static final String SCORES_12 = "/scores/nfl/regular/2026/12";
    private static final String SCORES_13 = "/scores/nfl/regular/2026/13";
    private static final String SCORES_14 = "/scores/nfl/regular/2026/14";
    private static final String TRANSACTIONS_12 = SleeperStubs.LEAGUE_PATH + "/transactions/12";
    private static final String TRANSACTIONS_11 = SleeperStubs.LEAGUE_PATH + "/transactions/11";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private TelegramWebhook webhook;

    /** A quiet week-12 league with two full rosters and three weeks to play. */
    private void week12League() {
        clock.set(WEEK_12);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-league.json", "players-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH,
                "sleeper/league-in-season.json", "league-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-trade.json", "rosters-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.USERS_PATH,
                "sleeper/users-league.json", "users-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.STATE_PATH,
                "sleeper/state-nfl-week12.json", "state-v1");
        SleeperStubs.stubJson(sleeper, PROJECTIONS_12, "sleeper/projections-trade.json",
                "projections-w12");
        SleeperStubs.stubJson(sleeper, PROJECTIONS_13, "sleeper/projections-trade.json",
                "projections-w13");
        SleeperStubs.stubJson(sleeper, PROJECTIONS_14, "sleeper/projections-trade.json",
                "projections-w14");
        SleeperStubs.stubJson(sleeper, SCORES_12, "sleeper/scores-trade-w12.json", "scores-w12");
        SleeperStubs.stubJson(sleeper, SCORES_13, "sleeper/scores-trade-w13.json", "scores-w13");
        SleeperStubs.stubJson(sleeper, SCORES_14, "sleeper/scores-trade-w14.json", "scores-w14");
        SleeperStubs.stubJson(sleeper, TRANSACTIONS_12, "sleeper/transactions-none.json",
                "transactions-v1");
        SleeperStubs.stubJson(sleeper, TRANSACTIONS_11, "sleeper/transactions-none.json",
                "transactions-w11");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "A quiet week.");
    }

    /** Ask tools read the stored Snapshot, so a Check builds one first. */
    private void snapshotOfTheLeague() {
        week12League();
        checkRunner.runCheck();
        llm.resetAll();
        telegram.resetRequests();
        OutboundStubs.telegramOk(telegram);
    }

    private void ask(String text) {
        assertThat(webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage(text)))
                .isEqualTo(WebhookResult.OK);
    }

    private static String trade(String gets, String gives) {
        return "{\"manager\":\"GridironGoblin\",\"gets\":\"%s\",\"gives\":\"%s\"}"
                .formatted(gets, gives);
    }

    /** What the model was handed, as it appears inside the chat request. */
    private static String field(String name, String value) {
        return "\\\"%s\\\":\\\"%s\\\"".formatted(name, value);
    }

    /**
     * TE Depth 06 projects 16.0 a week, so 48.0 over the three weeks
     * left. A tight end is scarce (1.10) and he walks into the user's
     * tight end slot over Travis Kelce (1.10), which values him at
     * 58.1. James Cook projects 12.0 a week, so 36.0; a running back
     * carries 1.05 and he is the first back off the user's own bench,
     * which is cover rather than an upgrade (1.00), so 37.8. The gap is
     * 34.9% of the larger side, which is a clear edge, and a clear edge
     * states itself at High.
     */
    @Test
    void aClearEdgePricesBothSidesAndRidesAtHighConfidence() {
        snapshotOfTheLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 06", "James Cook"),
                "Take it: you win that one clearly.");

        ask("should I trade James Cook to GridironGoblin for TE Depth 06?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(field("verdict", "clear edge")))
                .withRequestBody(containing(field("favours", "you")))
                .withRequestBody(containing(field("gap", "34.9%")))
                .withRequestBody(containing(field("confidence", "HIGH")))
                .withRequestBody(containing(field("restOfSeasonWeeks", "weeks 12 to 14")))
                .withRequestBody(containing(field("restOfSeasonPoints", "48.0")))
                .withRequestBody(containing(field("scarcity", "1.10")))
                .withRequestBody(containing(field("rosterFit", "1.10")))
                .withRequestBody(containing(field("value", "58.1")))
                .withRequestBody(containing(field("value", "37.8")))
                .withRequestBody(containing(field("net", "20.3"))));
    }

    /**
     * The same math runs from the partner's side. He gives up the tight
     * end he starts and gets a running back who starts for him, so his
     * own net is the mirror of the user's: he loses 16.5.
     */
    @Test
    void bothTeamsArePricedByTheSameYardstick() {
        snapshotOfTheLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 06", "James Cook"),
                "He has no reason to say yes.");

        ask("would GridironGoblin take Cook for his tight end?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(field("team", "you")))
                .withRequestBody(containing(field("team", "GridironGoblin")))
                .withRequestBody(containing(field("net", "20.3")))
                .withRequestBody(containing(field("net", "-16.5")))
                .withRequestBody(containing(field("value", "41.6"))));
    }

    /**
     * QB Depth 05 projects 18.0 a week - 54.0 left - and a Superflex
     * league makes a quarterback the scarcest thing there is (1.20). He
     * starts in the user's second quarterback slot (1.10), so 71.3.
     * Justin Jefferson projects 20.0 a week, 60.0 left, but a receiver
     * is the position the waiver wire answers best (1.00) even though
     * he starts (1.10), so 66.0. That is 7.4%: a slight edge, and a
     * comparison of two estimates rides at Medium.
     */
    @Test
    void aSlightEdgeIsSaidToBeSlightAndRidesAtMedium() {
        snapshotOfTheLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("QB Depth 05", "Justin Jefferson"),
                "It leans your way, but not far.");

        ask("Jefferson for QB Depth 05, worth it?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(field("verdict", "slight edge")))
                .withRequestBody(containing(field("favours", "you")))
                .withRequestBody(containing(field("gap", "7.4%")))
                .withRequestBody(containing(field("confidence", "MEDIUM")))
                .withRequestBody(containing(field("scarcity", "1.20")))
                .withRequestBody(containing(field("value", "71.3")))
                .withRequestBody(containing(field("value", "66.0"))));
    }

    /**
     * WR Depth 05 and Bijan Robinson both project 15.0 a week and both
     * start for the team that would hold them, so the only thing
     * between them is that a running back is scarcer than a receiver.
     * That is 4.8% - inside the even band - so the answer is a coin
     * flip with the lean stated and no more.
     */
    @Test
    void anEvenTradeIsACoinFlipWithTheLeanStated() {
        snapshotOfTheLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("WR Depth 05", "Bijan Robinson"),
                "It is a coin flip that leans his way.");

        ask("Bijan for WR Depth 05?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(field("verdict", "even")))
                .withRequestBody(containing(field("favours", "GridironGoblin")))
                .withRequestBody(containing(field("gap", "4.8%")))
                .withRequestBody(containing(field("confidence", "MEDIUM")))
                .withRequestBody(containing(field("value", "49.5")))
                .withRequestBody(containing(field("value", "52.0")))
                .withRequestBody(containing("which is a coin flip")));
    }

    /**
     * A player behind a better one on the bench of the team that would
     * hold him is worth less to that team, whichever team it is.
     * Tyler Lockett sits behind Jalen Reagor on the user's bench, and
     * TE Depth 13 would sit behind Dallas Goedert on it, so both sides
     * of this trade carry the buried factor.
     */
    @Test
    void aBuriedPlayerIsWorthLessToTheTeamThatWouldHoldHim() {
        snapshotOfTheLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 13", "Tyler Lockett"),
                "Neither of them plays for you.");

        ask("Lockett for TE Depth 13?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(field("rosterFit", "0.90")))
                .withRequestBody(containing("sits behind 1 better WR on that bench"))
                .withRequestBody(containing("sits behind 1 better TE on that bench"))
                .withRequestBody(containing(field("value", "27.0")))
                .withRequestBody(containing(field("value", "14.9"))));
    }

    /**
     * Otto prices no draft pick, so a pick counts zero and the answer
     * says in as many words that the verdict cannot see it. Saying
     * nothing would let a pick-for-player trade read as a robbery.
     */
    @Test
    void aDraftPickCountsZeroAndTheAnswerSaysItIsUnvalued() {
        snapshotOfTheLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 06 and a 2027 1st round pick", "Josh Jacobs"),
                "I cannot price the pick, so read this as the players alone.");

        ask("TE Depth 06 plus a 2027 1st for Josh Jacobs?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(field("kind", "draft pick")))
                .withRequestBody(containing(field("asset", "a 2027 1st round pick")))
                .withRequestBody(containing("Otto puts no price on a draft pick"))
                .withRequestBody(containing("Draft picks count zero")));
    }

    /**
     * A bye scores nothing, so a player with a bye left in the season
     * is worth less than the same player without one. Kansas City sit
     * out week 14 in this fixture, which takes Travis Kelce from 36.0
     * to 24.0 over the three weeks left.
     */
    @Test
    void aByeWeekCountsZeroTowardsTheRestOfTheSeason() {
        week12League();
        SleeperStubs.stubJson(sleeper, SCORES_14, "sleeper/scores-trade-w14-kc-bye.json",
                "scores-w14");
        checkRunner.runCheck();
        llm.resetAll();
        telegram.resetRequests();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 06", "Travis Kelce"),
                "Kelce has a bye left, which is a week of nothing.");

        ask("Kelce for TE Depth 06?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(field("restOfSeasonPoints", "24.0")))
                .withRequestBody(containing(field("byes", "on bye in week 14")))
                .withRequestBody(containing("a bye week counting zero")));
    }

    /**
     * A trade is agreed on need, not on value, so the answer says what
     * the partner is short of and which of the user's spare parts he
     * has a reason to pay for.
     */
    @Test
    void theLeverageNoteMatchesThePartnersGapsToTheUsersSurplus() {
        week12League();
        // The same league with one back on GridironGoblin's roster: he
        // cannot fill his own second running back slot, and the user has
        // four backs above replacement level to sell him one from.
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-trade-thin-rb.json", "rosters-v1");
        checkRunner.runCheck();
        llm.resetAll();
        telegram.resetRequests();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 06", "James Cook"),
                "He needs a back, and you have four.");

        ask("what does GridironGoblin need from me?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("leverage"))
                .withRequestBody(containing(
                        "GridironGoblin is short at RB: 1 startable for 2 starting slots"))
                .withRequestBody(containing("you hold 4 above replacement there"))
                .withRequestBody(containing("That is what he has a reason to pay for"))
                .withRequestBody(containing("Yours to spend")));
    }

    /**
     * The partner's need is read where the user's own is: against
     * replacement level, position by position. A trade with no need on
     * the other side is worth knowing about before the user offers.
     */
    @Test
    void aPartnerShortOfNothingIsSaidToBeShortOfNothingWorthTrading() {
        snapshotOfTheLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 06", "James Cook"),
                "He is deep everywhere you are.");

        ask("does GridironGoblin need anything I have?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("but you are no deeper there than he is"))
                .withRequestBody(containing("Yours to spend: Deep at RB")));
    }

    /**
     * A trade needs two teams. Asking about a player nobody in the
     * league can trade away, or about a manager who is not there, is
     * answered plainly rather than priced.
     */
    @Test
    void aTradeOtherThanBetweenTwoTeamsIsRefusedInWords() {
        snapshotOfTheLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                "{\"manager\":\"NobodyAtAll\",\"gets\":\"TE Depth 06\","
                        + "\"gives\":\"James Cook\"}",
                "There is no such manager in your league.");

        ask("trade Cook to NobodyAtAll for TE Depth 06?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("no manager in this league matches")));
    }
}
