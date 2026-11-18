package otto;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.harness.Fixtures;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.webhook.TelegramWebhook;
import otto.webhook.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trade evaluation: the user Asks whether a trade is worth taking and
 * gets four numbers - his own gain and cost, and the partner's - each
 * priced against that team's own roster, plus a verdict from his side
 * alone and a leverage note.
 *
 * <p>The scenario runs the league in week 12 of a regular season that
 * ends in week 14, so the rest of the season is three weeks and a
 * player is worth three weeks of projection. The projection fixture
 * prices every rostered player at a round number, which puts
 * Replacement Level at 10.5 points for a quarterback, 9.0 for a running
 * back, 15.0 for a receiver and 16.5 for a tight end over those three
 * weeks. Each verdict band is forced by that arithmetic rather than by
 * the assertion.
 *
 * <p>The user holds four running backs and six receivers above
 * replacement and a thin pair of tight ends. GridironGoblin is the
 * mirror of that: the two best tight ends in the league and no receiver
 * the user would want.
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
        resetOutboundStubsAfterCheck();
    }

    private void resetOutboundStubsAfterCheck() {
        llm.resetAll();
        telegram.resetRequests();
        OutboundStubs.telegramOk(telegram);
    }

    private void snapshotInWeek(String stateFixture, int week) {
        week12League();
        SleeperStubs.stubJson(sleeper, SleeperStubs.STATE_PATH,
                stateFixture, "state-week-" + week);
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH,
                "sleeper/league-in-season.json", "league-week-" + week);
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH + "/transactions/" + week,
                "sleeper/transactions-none.json", "transactions-week-" + week);
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH + "/transactions/" + (week - 1),
                "sleeper/transactions-none.json", "transactions-week-" + (week - 1));
        checkRunner.runCheck();
        resetOutboundStubsAfterCheck();
    }

    /** The same week-12 league read under another league document. */
    private void snapshotOfTheLeagueAs(String leagueFixture) {
        week12League();
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH, leagueFixture,
                "league-" + leagueFixture);
        checkRunner.runCheck();
        resetOutboundStubsAfterCheck();
    }

    private static void fantasyCalcAnswers(boolean dynasty, String fixture) {
        fantasyCalc.stubFor(get(urlPathEqualTo("/values/current"))
                .withQueryParam("isDynasty", equalTo(String.valueOf(dynasty)))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(Fixtures.read(fixture))));
    }

    /** A dynasty or keeper league with both FantasyCalc lists and the league's moved picks. */
    private void longTermLeague(String leagueFixture) {
        snapshotOfTheLeagueAs(leagueFixture);
        fantasyCalcAnswers(false, "fantasycalc/values-trade-redraft.json");
        fantasyCalcAnswers(true, "fantasycalc/values-trade-dynasty.json");
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH + "/traded_picks",
                "sleeper/traded-picks-trade.json", "traded-picks-v1");
    }

    private void ask(String text) {
        assertThat(webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage(text)))
                .isEqualTo(WebhookResult.OK);
    }

    private static String trade(String gets, String gives) {
        return "{\"manager\":\"GridironGoblin\",\"gets\":\"%s\",\"gives\":\"%s\"}"
                .formatted(gets, gives);
    }

    /** A number the model was handed, as it appears inside the chat request. */
    private static String number(String name, String value) {
        return "\\\"%s\\\":%s".formatted(name, value);
    }

    /** What the model was handed, as it appears inside the chat request. */
    private static String field(String name, String value) {
        return "\\\"%s\\\":\\\"%s\\\"".formatted(name, value);
    }

    @Test
    void aTradeBeforeTheDeadlineIsUnchanged() {
        snapshotOfTheLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 06", "James Cook"),
                "Take it: you win that one clearly.");

        ask("should I trade James Cook to GridironGoblin for TE Depth 06?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(field("verdict", "clear edge")))
                .withRequestBody(containing("\\\"yourRosterAfter\\\""))
                .withRequestBody(containing("\\\"partnerRosterAfter\\\""))
                .withRequestBody(containing("TE Depth 06"))
                .withRequestBody(notContaining("\\\"tradeDeadline\\\":")));
    }

    @Test
    void aTradeInTheDeadlineWeekSaysWhenTradesClose() {
        snapshotInWeek("sleeper/state-nfl-week13.json", 13);
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 06", "James Cook"),
                "The deadline is this week, so decide now.");

        ask("should I trade James Cook to GridironGoblin for TE Depth 06?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(field("tradeDeadline",
                        "The trade deadline is this week. Trades close when week 13's last "
                                + "game ends, usually Monday night. A trade has to be proposed "
                                + "and accepted before then, and its review period can finish "
                                + "afterward.")))
                .withRequestBody(containing("leverage")));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(
                        "The deadline is this week, so decide now."))));
    }

    @Test
    void aTradeAfterTheDeadlineIsMarkedBeforeItsPriceAndCarriesNoLeverage() {
        snapshotInWeek("sleeper/state-nfl-week14.json", 14);
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 06", "James Cook"),
                "The deadline passed after week 13. Here is what it would have been worth.");

        ask("should I trade James Cook to GridironGoblin for TE Depth 06?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(field("tradeDeadline",
                        "The trade deadline passed after week 13. This trade can no longer be "
                                + "submitted, but here is what it would have been worth.")))
                .withRequestBody(containing("\\\"leverage\\\":[]"))
                .withRequestBody(notContaining("reason to pay for")));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(
                        "The deadline passed after week 13. Here is what it would have been "
                                + "worth."))));
    }

    @Test
    void aTradeOnTuesdayMorningOfTheDeadlineWeekIsMarkedAsTooLate() {
        snapshotInWeek("sleeper/state-nfl-week13.json", 13);
        clock.set(Instant.parse("2026-12-08T14:00:00Z"));
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 06", "James Cook"),
                "The deadline passed after week 13. Here is what it would have been worth.");

        ask("should I trade James Cook to GridironGoblin for TE Depth 06?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(field("tradeDeadline",
                        "The trade deadline passed after week 13. This trade can no longer be "
                                + "submitted, but here is what it would have been worth.")))
                .withRequestBody(containing("\\\"leverage\\\":[]")));
    }

    @Test
    void aTradeInALeagueWithTradesTurnedOffIsPricedButMarkedAsImpossible() {
        snapshotOfTheLeagueAs("sleeper/league-in-season-trades-disabled.json");
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 06", "James Cook"),
                "Trades are off in this league, but you would win that one.");

        ask("should I trade James Cook to GridironGoblin for TE Depth 06?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(field("tradeDeadline",
                        "Trades are turned off in this league for the whole season. This "
                                + "trade can't be submitted, but here is what it would be "
                                + "worth.")))
                .withRequestBody(containing(field("verdict", "clear edge")))
                .withRequestBody(containing("\\\"leverage\\\":[]")));
    }

    @Test
    void aLeagueWithoutADeadlineDoesNotReadAsDeadlineWeekZero() {
        week12League();
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH,
                "sleeper/league-in-season-no-trade-deadline.json", "league-no-deadline");
        checkRunner.runCheck();
        resetOutboundStubsAfterCheck();
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 06", "James Cook"),
                "Take it: you win that one clearly.");

        ask("should I trade James Cook to GridironGoblin for TE Depth 06?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(field("verdict", "clear edge")))
                .withRequestBody(notContaining("\\\"tradeDeadline\\\":"))
                .withRequestBody(notContaining("week 0")));
    }

    /**
     * TE Depth 06 projects 16.0 a week, so 48.0 over the three weeks
     * left and 34.5 of that above the tight end replacement level of
     * 13.5: the league's best lineups start 14 tight ends, two of them in
     * flex spots. A tight end is scarce (1.10) and he walks into the
     * user's tight end slot (1.10), so he is worth 41.7 to the user.
     * James Cook is worth 27.0 above the running back line, carries 1.05,
     * and is only the first back off the user's own bench (1.00), so
     * giving him up costs 28.4. The user nets 13.4, which is 32.1% of his
     * larger side: a clear edge, and a clear edge states itself at High.
     */
    @Test
    void aClearEdgePricesFourNumbersAndRidesAtHighConfidence() {
        snapshotOfTheLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 06", "James Cook"),
                "Take it: you win that one clearly.");

        ask("should I trade James Cook to GridironGoblin for TE Depth 06?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(field("verdict", "clear edge")))
                .withRequestBody(containing(field("favours", "you")))
                .withRequestBody(containing(field("gap", "32.1%")))
                .withRequestBody(containing(field("confidence", "HIGH")))
                .withRequestBody(containing(field("restOfSeasonWeeks", "weeks 12 to 14")))
                .withRequestBody(containing(field("restOfSeasonPoints", "48.0")))
                .withRequestBody(containing(field("aboveReplacement", "34.5")))
                .withRequestBody(containing(field("scarcity", "1.10")))
                .withRequestBody(containing(field("rosterFit", "1.10")))
                // Cook is neither an upgrade nor buried: he is the cover.
                .withRequestBody(containing(field("rosterFit", "1.00")))
                .withRequestBody(containing("is the first RB off that bench"))
                // The user's own four numbers, and the partner's.
                .withRequestBody(containing(field("net", "13.4")))
                .withRequestBody(containing(field("net", "-10.6")))
                .withRequestBody(containing(field("total", "41.7")))
                .withRequestBody(containing(field("total", "28.4")))
                .withRequestBody(containing(field("total", "31.2"))));
    }

    /**
     * The point of the four numbers. James Cook is only the first back
     * off the user's bench, so giving him up costs little;
     * GridironGoblin starts him. GridironGoblin's second tight end only
     * reaches the user's flex, and he walks into the user's empty tight
     * end slot. Both managers come out ahead, which is why a trade
     * happens at all, and Otto says so rather than warning the user off a
     * deal that is good for him.
     */
    @Test
    void aTradeCanBeGoodForBothTeamsAndTheAnswerSaysSo() {
        snapshotOfTheLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 10", "James Cook"),
                "You both win: he needs the back, you need the tight end.");

        ask("James Cook to GridironGoblin for TE Depth 10?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(field("verdict", "slight edge")))
                .withRequestBody(containing(field("favours", "you")))
                .withRequestBody(containing(field("gap", "8.1%")))
                // The user is up 2.5 and the partner is up 0.3: both nets
                // are positive at once.
                .withRequestBody(containing(field("net", "2.5")))
                .withRequestBody(containing(field("net", "0.3")))
                .withRequestBody(containing(
                        "GridironGoblin gains from this too, which is why they would agree"))
                .withRequestBody(containing("is the first RB off that bench")));
    }

    /**
     * The verdict is the user's alone. This trade costs GridironGoblin
     * 3.5 and gains the user 3.5, and Otto still reports it as a slight
     * edge to the user with the partner's loss beside it as context. It
     * is not Otto's business to talk a user out of a trade another
     * manager is willing to make.
     */
    @Test
    void theVerdictReadsTheUsersNetAloneAndNeverThePartners() {
        snapshotOfTheLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("QB Depth 05", "Christian McCaffrey"),
                "It leans your way, and he pays for it.");

        ask("McCaffrey for QB Depth 05, worth it?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(field("verdict", "slight edge")))
                .withRequestBody(containing(field("favours", "you")))
                .withRequestBody(containing(field("gap", "6.3%")))
                .withRequestBody(containing(field("confidence", "MEDIUM")))
                // Superflex makes the quarterback the scarcest thing there is.
                .withRequestBody(containing(field("scarcity", "1.20")))
                .withRequestBody(containing(field("net", "3.5")))
                .withRequestBody(containing(field("net", "-3.5")))
                .withRequestBody(containing(
                        "GridironGoblin loses on this, so they have a reason to say no"))
                .withRequestBody(containing("The verdict is yours alone")));
    }

    /**
     * Justin Jefferson for QB Depth 05: the quarterback's Superflex
     * scarcity all but closes the gap to the best receiver in the
     * league, leaving 1.2%. That is inside the even band, so the answer
     * is a coin flip with the lean stated and no more.
     */
    @Test
    void anEvenTradeIsACoinFlipWithTheLeanStated() {
        snapshotOfTheLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("QB Depth 05", "Justin Jefferson"),
                "It is a coin flip that leans his way.");

        ask("Jefferson for QB Depth 05?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(field("verdict", "even")))
                .withRequestBody(containing(field("favours", "GridironGoblin")))
                .withRequestBody(containing(field("gap", "1.2%")))
                .withRequestBody(containing(field("confidence", "MEDIUM")))
                .withRequestBody(containing(field("net", "-0.7")))
                .withRequestBody(containing("which is a coin flip")));
    }

    /**
     * A player who projects below Replacement Level is worth nothing in
     * a trade: the manager can claim that player off the waiver wire
     * without giving anything up. RB Depth 25 projects 9.0 over the
     * three weeks left, no more than the 25th running back, the last one
     * the league's best lineups start, so he prices at zero however he
     * would fit.
     */
    @Test
    void aPlayerBelowReplacementLevelIsWorthNothingInATrade() {
        snapshotOfTheLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("RB Depth 25", "Tyler Lockett"),
                "He is free on the wire. Do not pay for him.");

        ask("Lockett for RB Depth 25?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(field("restOfSeasonPoints", "9.0")))
                .withRequestBody(containing(field("aboveReplacement", "0.0")))
                .withRequestBody(containing(field("value", "0.0")))
                .withRequestBody(containing(field("favours", "GridironGoblin"))));
    }

    /**
     * The starting lineup is the other half of the answer: what the
     * team would actually score. It counts no scarcity, so it can point
     * a different way from the priced net, and the answer says as much
     * rather than letting the two read as a contradiction.
     */
    @Test
    void theAnswerCarriesWhatEachStartingLineupWouldScore() {
        snapshotOfTheLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 06", "James Cook"),
                "Your starters go up twelve points.");

        ask("what does that trade do to my starting lineup?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(field("startersNow", "444.0")))
                .withRequestBody(containing(field("startersAfter", "456.0")))
                .withRequestBody(containing(field("startersDelta", "12.0")))
                .withRequestBody(containing(field("startersDelta", "-12.0")))
                .withRequestBody(containing("count positional scarcity")
                        .or(containing("counts positional scarcity"))));
    }

    /**
     * A redraft league starts over every year, so the market puts no
     * value on a pick there and the rest-of-season numbers can't see one
     * either. The answer says why the pick counts for nothing rather
     * than pricing it at zero in silence.
     */
    @Test
    void aDraftPickInARedraftLeagueCarriesNoValueAndTheAnswerSaysWhy() {
        snapshotOfTheLeague();
        fantasyCalcAnswers(false, "fantasycalc/values-trade-redraft.json");
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 06 and a 2027 1st round pick", "Josh Jacobs"),
                "The pick has no value in a redraft league.");

        ask("TE Depth 06 plus a 2027 1st for Josh Jacobs?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(field("kind", "draft pick")))
                .withRequestBody(containing(field("asset", "a 2027 1st round pick")))
                .withRequestBody(containing("A draft pick scores nothing this season"))
                .withRequestBody(containing("Draft picks carry no value in a redraft league"))
                .withRequestBody(notContaining("Draft picks count zero")));
    }

    /**
     * This league does not allow FAAB to move in a trade. Whoever gives
     * up more players replaces them off the wire for about a dollar, so
     * bidding money in a trade is noise. Otto reads the words and drops
     * them: no price, no note, nothing in the answer at all.
     */
    @Test
    void faabIsDroppedFromTheAnswerBecauseThisLeagueDoesNotTradeIt() {
        snapshotOfTheLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 06", "James Cook and $20 FAAB"),
                "The money is not part of a trade here.");

        ask("Cook plus 20 dollars of FAAB for TE Depth 06?");

        // The trade prices exactly as it does without the money, and the
        // money itself reaches no field of the answer. The raw words stay
        // visible only where the model quoted its own tool call back.
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(field("gap", "32.1%")))
                .withRequestBody(containing(field("total", "28.4")))
                .withRequestBody(notContaining(field("kind", "FAAB")))
                .withRequestBody(notContaining(field("asset", "$20 FAAB")))
                .withRequestBody(notContaining("no price on FAAB")));
    }

    /**
     * A bye scores nothing, so a player with a bye left in the season is
     * worth less than the same player without one. Kansas City sit out
     * week 14 in this fixture, which takes Travis Kelce from 36.0 to
     * 24.0 over the three weeks left, and from 22.5 above the tight end
     * line to 10.5.
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
                .withRequestBody(containing(field("aboveReplacement", "10.5")))
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
                .withRequestBody(containing("That is what they have a reason to pay for"))
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
                .withRequestBody(notContaining("reason to pay for"))
                .withRequestBody(containing("Yours to spend: Deep at RB")));
    }

    /**
     * A user who names a player on the wrong side of the deal wants to
     * be told which player he means, not handed an error. The trade is
     * still priced and the answer says whose roster he is really on.
     */
    @Test
    void aPlayerNamedOnTheWrongSideIsStillPricedWithTheMixUpNamed() {
        snapshotOfTheLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("Travis Kelce", "TE Depth 06"),
                "You have those two the wrong way round.");

        ask("Kelce from GridironGoblin for TE Depth 06?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(
                        "Travis Kelce is not on GridironGoblin's roster, so check who you meant"))
                .withRequestBody(containing(
                        "TE Depth 06 is not on your roster, so check who you meant")));
    }

    /**
     * A player still carries his full price wherever he is named, so a
     * user who put one on the wrong side could otherwise buy himself a
     * High-confidence verdict for a trade the partner cannot deliver.
     * The confidence drops to Medium and the answer says the names are
     * wrong, rather than stating a deal nobody could agree to.
     */
    @Test
    void aTradeNamingAPlayerNobodyCanDeliverNeverStatesItselfAtHigh() {
        snapshotOfTheLeague();
        // Justin Jefferson is the user's own, so GridironGoblin cannot
        // send him. Priced naively this is a landslide in the user's
        // favour, which is exactly the answer that must not be stated.
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 06 and Justin Jefferson", "James Cook"),
                "Check the names: Jefferson is already yours.");

        ask("Cook for TE Depth 06 and Justin Jefferson?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(field("verdict", "clear edge")))
                .withRequestBody(containing(field("confidence", "MEDIUM")))
                .withRequestBody(containing("isn't a trade either manager could agree to"))
                .withRequestBody(containing(
                        "Justin Jefferson is not on GridironGoblin's roster")));
    }

    /**
     * Two players sent together must not be read as blocking each
     * other. Josh Jacobs is buried behind James Cook on the user's
     * bench, but in a trade that sends both of them Cook is gone, so
     * Jacobs is priced as the cover he would be.
     */
    @Test
    void twoPlayersOnTheSameSideAreNotPricedAsBlockingEachOther() {
        snapshotOfTheLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 06", "James Cook and Josh Jacobs"),
                "Both backs leave, so neither is buried behind the other.");

        ask("Cook and Jacobs for TE Depth 06?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                // Jacobs at 1.00 cover, not 0.90 buried behind a back who
                // is leaving in the same deal.
                .withRequestBody(containing(field("value", "25.2")))
                .withRequestBody(notContaining("sits behind 1 better RB on that bench")));
    }

    /**
     * There is only one of each player, so naming one twice on a side
     * would price two copies of him and buy a verdict for a trade the
     * partner could never deliver. The same holds for a player named on
     * both sides at once: he would not move.
     */
    @Test
    void aPlayerNamedTwiceIsRefusedRatherThanPricedTwice() {
        snapshotOfTheLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 06 and TE Depth 06", "Tyler Lockett"),
                "He only has one of him.");

        ask("both of GridironGoblin's TE Depth 06 for Lockett?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("you name TE Depth 06 twice on the same side"))
                .withRequestBody(notContaining(field("confidence", "HIGH"))));
    }

    @Test
    void aPlayerOnBothSidesIsRefusedBecauseHeWouldNotMove() {
        snapshotOfTheLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 06", "James Cook and TE Depth 06"),
                "He is on both sides of that.");

        ask("Cook and TE Depth 06 for TE Depth 06?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(
                        "TE Depth 06 is on both sides of this trade, so he would not move")));
    }

    /**
     * A side Otto can put no price on leaves the verdict blind to half
     * the trade. It is still reported, because the user asked, but it
     * never states itself at High.
     */
    @Test
    void aSideWithNothingPriceableNeverStatesItselfAtHigh() {
        snapshotOfTheLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 06", "a 2027 1st round pick"),
                "I cannot price what you would send.");

        ask("my 2027 first for TE Depth 06?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(field("confidence", "MEDIUM")))
                .withRequestBody(containing(
                        "One side of this trade holds nothing I can put a price on")));
    }

    /**
     * A trade needs two teams. Asking about a manager who is not there
     * is answered plainly rather than priced.
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

    /**
     * A one-quarterback league fills one quarterback slot a team. The
     * snapshot holds twelve rosters, so twelve quarterbacks start and QB
     * Depth 05 is priced against the twelfth, not the twenty-third a
     * Superflex lineup would start, and carries no Superflex premium.
     */
    @Test
    void aOneQuarterbackLeagueDoesNotPriceQuarterbacksLikeSuperflex() {
        snapshotOfTheLeagueAs("sleeper/league-market-1qb-half-ppr.json");
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("QB Depth 05", "Justin Jefferson"),
                "Quarterbacks are cheap in a one-quarterback league.");

        ask("Jefferson for QB Depth 05, worth it?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(notContaining(field("scarcity", "1.20")))
                .withRequestBody(containing("ranks 12th among QBs"))
                .withRequestBody(containing("only one quarterback")));
    }

    /**
     * GridironGoblin holds QB Depth 05, who projects 18.0 a week, and QB
     * Depth 20, who projects 8.0. In this twelve-team Superflex league
     * every team but his starts two quarterbacks; his best lineup puts a
     * receiver in the Superflex spot over QB Depth 20. So 23 quarterbacks
     * start, the 23rd quarterback's 4.0 is replacement, and both of his
     * quarterbacks are still startable.
     */
    @Test
    void aSuperflexLeaguesQuarterbackDepthCountsTheQuarterbacksItsBestLineupsStart() {
        snapshotOfTheLeague();
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_team_roster",
                "{\"manager\":\"GridironGoblin\"}", "He has two startable quarterbacks.");

        ask("what does GridironGoblin have?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(quarterbackDepth("4.0", 2))));
    }

    /**
     * The same rosters in a league that starts one quarterback a team:
     * each of the twelve rosters starts one, so the twelfth quarterback's
     * 9.5 is replacement and QB Depth 20 falls below it.
     */
    @Test
    void aOneQuarterbackLeaguesQuarterbackDepthCountsOneStartingQuarterbackATeam() {
        snapshotOfTheLeagueAs("sleeper/league-market-1qb-half-ppr.json");
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_team_roster",
                "{\"manager\":\"GridironGoblin\"}", "He has one startable quarterback.");

        ask("what does GridironGoblin have?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(quarterbackDepth("9.5", 1))));
    }

    /** The quarterback line of a team's depth, as it appears inside the chat request. */
    private static String quarterbackDepth(String replacement, int aboveReplacement) {
        return "%s,%s,%s".formatted(field("position", "QB"), field("replacement", replacement),
                number("aboveReplacement", String.valueOf(aboveReplacement)));
    }

    /**
     * A redraft league plays for this season alone, so the
     * rest-of-season fit decides, and the market says whether the other
     * manager would accept. On FantasyCalc James Cook is worth 6392 and
     * TE Depth 06 5200, so GridironGoblin gets more value than he gives
     * and would likely say yes to a trade the fit calls a clear edge for
     * the user.
     */
    @Test
    void aRedraftTradeIsDecidedByFitWithTheMarketSayingWhetherHeWouldAccept() {
        snapshotOfTheLeague();
        fantasyCalcAnswers(false, "fantasycalc/values-trade-redraft.json");
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 06", "James Cook"),
                "Take it, and he should say yes.");

        ask("should I trade James Cook to GridironGoblin for TE Depth 06?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(field("verdict", "clear edge")))
                .withRequestBody(containing(field("favours", "you")))
                .withRequestBody(containing("What the trade does for your lineup the rest of this "
                        + "season decides this call"))
                .withRequestBody(containing(
                        "On market value alone this is a clear edge for GridironGoblin"))
                .withRequestBody(containing(number("net", "-1192")))
                .withRequestBody(containing("GridironGoblin gets at least about what they give"))
                .withRequestBody(containing("12-team Superflex PPR redraft")));
    }

    /**
     * The market doesn't model TE premium, so the league's own scoring
     * carries it. TE Depth 06 projects 4 catches a week here: in this
     * league's PPR with half a point more for a tight end's catch he
     * scores 22.0 a week, 66.0 over the three weeks left, where a
     * league without the bonus scores him 60.0.
     */
    @Test
    void tePremiumIsCountedThroughTheLeaguesOwnScoring() {
        week12League();
        tightEndCatches();
        checkRunner.runCheck();
        resetOutboundStubsAfterCheck();
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 06", "James Cook"),
                "His catches are worth more in your league.");

        ask("Cook for TE Depth 06?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(field("restOfSeasonPoints", "66.0")))
                .withRequestBody(containing("Your league pays tight ends an extra 0.5 points a "
                        + "catch. The market values don't count that")));
    }

    @Test
    void aLeagueWithoutTePremiumScoresATightEndsCatchLikeAnyOther() {
        week12League();
        tightEndCatches();
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH,
                "sleeper/league-in-season-no-te-premium.json", "league-no-te-premium");
        checkRunner.runCheck();
        resetOutboundStubsAfterCheck();
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 06", "James Cook"),
                "A catch is a catch here.");

        ask("Cook for TE Depth 06?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(field("restOfSeasonPoints", "60.0")))
                .withRequestBody(notContaining("pays tight ends an extra")));
    }

    private void tightEndCatches() {
        SleeperStubs.stubJson(sleeper, PROJECTIONS_12, "sleeper/projections-trade-te-catches.json",
                "projections-catches-w12");
        SleeperStubs.stubJson(sleeper, PROJECTIONS_13, "sleeper/projections-trade-te-catches.json",
                "projections-catches-w13");
        SleeperStubs.stubJson(sleeper, PROJECTIONS_14, "sleeper/projections-trade-te-catches.json",
                "projections-catches-w14");
    }

    /**
     * A keeper's worth runs into next season, so the market decides. In
     * a three-keeper league Bijan Robinson is his team's most valuable
     * player and a likely keeper, so his redraft value carries a keeper
     * bonus on top: his dynasty value less Patrick Mahomes's, the player
     * the user would keep in his spot instead. That brings him to 13197,
     * short of the 14900 the two players coming back are worth, so the
     * market gives the user a slight edge.
     */
    @Test
    void aKeeperTradeIsDecidedByMarketValueWithTheKeeperBonusCounted() {
        longTermLeague("sleeper/league-keeper.json");
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 06 and RB Depth 08", "Bijan Robinson"),
                "Take it.");

        ask("Bijan for TE Depth 06 and RB Depth 08?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Market value decides this call. In a keeper league"))
                .withRequestBody(containing("He's a likely keeper, so his 8945 redraft value "
                        + "carries a 4252 keeper bonus on top"))
                .withRequestBody(containing("he uses a keeper spot you'd otherwise give to "
                        + "Patrick Mahomes"))
                .withRequestBody(containing(number("total", "13197")))
                .withRequestBody(containing(number("total", "14900")))
                .withRequestBody(containing("market value GridironGoblin gives up 11.4% more "
                        + "than they get"))
                .withRequestBody(containing("For your lineup this season alone this is")));
    }

    /**
     * Picks are priced in dynasty from the pick the partner actually
     * holds. GridironGoblin holds his own 2027 1st and TwoPointTina's,
     * and the user asked for an early one, which is TwoPointTina's: she
     * is last in the standings. FantasyCalc prices an early 2027 1st at
     * 4982 against James Cook's 5166, inside 5%, so the trade is a coin
     * flip on the measure that decides it.
     */
    @Test
    void aDynastyTradePricesTheEarlyPickThePartnerHolds() {
        longTermLeague("sleeper/league-dynasty.json");
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("2027 early 1st", "James Cook"),
                "That's a fair price for Cook.");

        ask("Cook for GridironGoblin's early 2027 first?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Market value decides this call. In a dynasty league"))
                .withRequestBody(containing(field("asset", "2027 early 1st-round pick")))
                .withRequestBody(containing(number("value", "4982")))
                .withRequestBody(containing("This is TwoPointTina's pick, which GridironGoblin "
                        + "got in a trade"))
                .withRequestBody(containing(field("verdict", "even")))
                .withRequestBody(notContaining("Draft picks count zero")));
    }

    /**
     * A 1st named without its place in the round is the partner's own,
     * placed from where he stands: first of twelve picks late. The market
     * has no late 2027 1st, so the pick is priced as a 2027 1st.
     */
    @Test
    void aPickNamedWithoutItsPlaceIsPlacedFromTheStandings() {
        longTermLeague("sleeper/league-dynasty.json");
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("2027 1st", "Josh Jacobs"),
                "His pick will fall late.");

        ask("Jacobs for GridironGoblin's 2027 first?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("GridironGoblin is 1st of 12 in the standings, so "
                        + "the pick should fall late in the round"))
                .withRequestBody(containing("GridironGoblin holds 2 2027 1sts, and I priced the "
                        + "one from GridironGoblin"))
                .withRequestBody(containing(number("value", "2892"))));
    }

    /**
     * The user's 2028 1st went to WaiverWizard, so he can't send it. The
     * trade is still priced, because he may have mixed up his picks, but
     * it never states itself at High.
     */
    @Test
    void aPickTheUserNoLongerHoldsIsNamedAndNeverStatesItselfAtHigh() {
        longTermLeague("sleeper/league-dynasty.json");
        OutboundStubs.llmCallsToolThenPhrases(llm, "evaluate_trade",
                trade("TE Depth 06", "2028 1st"),
                "You already traded that pick.");

        ask("my 2028 first for TE Depth 06?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("You don't hold a 2028 1st. Your own went to "
                        + "WaiverWizard"))
                .withRequestBody(containing(field("confidence", "MEDIUM")))
                .withRequestBody(containing("isn't a trade either manager could agree to")));
    }

}
