package otto;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.alerts.MuteStore;
import otto.check.CheckRunner;
import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;
import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.nflverse.DefenseVersusPositionBuilder;
import otto.nflverse.NflverseFeedService;
import otto.settings.SettingsStore;
import otto.settings.Trigger;
import otto.telegram.TelegramWebhook;
import otto.telegram.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Waivers: the Tuesday board and the same board by chat.
 *
 * The fixture week is 2026 week 2 with $100 of FAAB unspent. Five free
 * agents matter, and every number below is arithmetic anyone can repeat:
 *
 * <ul>
 * <li>Bucky Irving (RB, TB) projects 19.0 against an RB replacement of
 * 3.5, the best free agent on the board, so he takes all 50 projection
 * points. Tampa Bay's newest chart moved him RB2 to RB1 and Rachaad
 * White ahead of him is on IR, so he takes all 20 usage points. He is
 * the most-added player on Sleeper, so he takes all 15 trending points,
 * and his news names him the starter, so he takes all 15 news points.
 * Score 100, breakout, and a breakout at 100 bids 40-60% of $100.</li>
 * <li>Wandale Robinson (WR, NYG) meets Dallas, who give up 28.0 points
 * per game to receivers against a 14.5 average - almost twice as much,
 * so about 14 of his own 15.5 projected points are the matchup, which
 * is more than half of the 10.5 that lift him above replacement. That
 * is a stream, and a stream is capped at 10% however good the score. He
 * also fills the receiver slot Puka Nacua's bye leaves illegal, which
 * adds five points to both ends: 10-15% of $100.
 * <p>He is also the false positive the breakout lanes have to refuse.
 * The week gave him 13 targets and 180 yards, but the Giants threw 58
 * times, so 13 targets is 22% of the offence - a normal share in a
 * pass-fest, not a bigger role. Volume the game script handed him is
 * not a breakout, and a share says so where a raw count does not.</li>
 * <li>Cade Otton (TE, TB) is a second stream against the softest
 * tight-end defence in the table, capped at 10% with no slot to fix.
 * Ray Davis (RB, BUF) and Michael Penix (QB, ATL) are solid: one meets
 * a defence tougher than average, the other's opponent is not in the
 * table at all, and an unknown matchup is never called a soft one.</li>
 * </ul>
 */
class WaiverScenarioTest extends WireSeamTest {

    /** Tuesday 2026-09-15 at 18:00 America/Los_Angeles, in summer time. */
    private static final Instant TUESDAY_EVENING = Instant.parse("2026-09-16T01:00:00Z");

    /**
     * Tuesday 2026-11-03 at 18:00 America/Los_Angeles. Daylight saving
     * ended on 1 November, so the same local evening is an hour later
     * in UTC than the September one.
     */
    private static final Instant WINTER_TUESDAY_EVENING = Instant.parse("2026-11-04T02:00:00Z");

    /**
     * Midnight at the end of that Tuesday, in Los Angeles. From here on
     * it is Wednesday, which is when claims clear, so the board is no
     * longer worth sending.
     */
    private static final Instant LAST_CALL = Instant.parse("2026-09-16T07:00:00Z");

    private static final String SEPTEMBER_BOARD = "alert:waiver:2026-09-15";
    private static final String NOVEMBER_BOARD = "alert:waiver:2026-11-03";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private DefenseVersusPositionBuilder defenseBuilder;

    @Autowired
    private EventLog eventLog;

    @Autowired
    private TelegramWebhook webhook;

    @Autowired
    private MuteStore muteStore;

    @Autowired
    private SettingsStore settings;

    /**
     * A Snapshot, the nflverse feeds and the nightly defence table, all
     * on disk. The lineup Alerts this first Check sends are not what
     * these scenarios observe, so the journals reset afterwards.
     */
    private void aWaiverWeekOnDisk() {
        aWaiverWeekOnDisk(NflverseStubs::waiverWeek);
    }

    private void aWaiverWeekOnDisk(Consumer<WireMockServer> nflverseFeeds) {
        SleeperStubs.waiverWeek(sleeper);
        nflverseFeeds.accept(nflverse);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "A lineup alert.");

        checkRunner.runCheck();
        feeds.updateIfDue();
        defenseBuilder.build();

        llm.resetAll();
        telegram.resetRequests();
        sleeper.resetRequests();
    }

    private void runCheckAt(Instant when) {
        clock.set(when);
        checkRunner.runCheck();
    }

    private Optional<Event> boardEvent(String key) {
        return eventLog.all().stream()
                .filter(event -> event.key().equals(key))
                .findFirst();
    }

    private void ask(String text) {
        assertThat(webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage(text)))
                .isEqualTo(WebhookResult.OK);
    }

    @Test
    void theTuesdayBoardScoresTagsAndPricesTheTopFiveFreeAgents() {
        aWaiverWeekOnDisk();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Five waiver targets for Wednesday.");

        runCheckAt(TUESDAY_EVENING);

        Event board = boardEvent(SEPTEMBER_BOARD).orElseThrow();
        assertThat(board.facts())
                .containsEntry("trigger", "waiver board")
                .containsEntry("week", "2026-w2")
                .containsEntry("remainingBudget", "100");

        // The breakout: every component full, the score capped at 100,
        // and the bid raised one band because the role change lasts.
        assertThat(board.facts().get("target1"))
                .contains("Bucky Irving", "score 100", "breakout", "bid $40-$60")
                .contains("projects 19.0, 15.5 above the RB replacement level of 3.5")
                .contains("moved him from RB2 to RB1")
                .contains("Rachaad White, ahead of him on that chart, is IR")
                .contains("Named the starter in Tampa Bay");

        // The stream: capped at 10% of the budget by the tag, then
        // lifted five points because he fills the bye-hit receiver slot.
        assertThat(board.facts().get("target2"))
                .contains("Wandale Robinson", "score 57", "stream", "bid $10-$15")
                .contains("capped at 5-10% for a one-week stream")
                .contains("fills a slot you cannot legally fill this week")
                .contains("no bench WR above replacement");

        // A second stream, and the cap with no slot to fix: Tampa Bay
        // meet the softest tight-end defence in the table, so 5-12%
        // becomes 5-10% and stops there.
        assertThat(board.facts().get("target3"))
                .contains("Cade Otton", "score 42", "stream", "bid $5-$10");
        assertThat(board.facts().get("target4"))
                .contains("Ray Davis", "score 21", "solid", "bid $0-$5")
                .contains("his news reads as negative");
        assertThat(board.facts().get("target5"))
                .contains("Michael Penix", "score 20", "solid", "bid $0-$5")
                .contains("you roster 2 quarterbacks");
        assertThat(board.facts()).doesNotContainKey("target6");

        // The TE cutoff is 12, and the fixture projects 13 tight ends,
        // so replacement is the twelfth of them rather than the worst.
        assertThat(board.facts().get("basis"))
                .contains("TE replacement level is 5.5 points: the TE12 projection this week")
                .contains("only 8 RBs are projected this week, fewer than the RB24 cutoff");

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Bucky Irving")));
    }

    @Test
    void theBoardGoesOutOnceAndOnlyAfterTuesdayEvening() {
        aWaiverWeekOnDisk();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Five waiver targets for Wednesday.");

        // A minute before 18:00 is not Tuesday evening yet.
        runCheckAt(TUESDAY_EVENING.minus(Duration.ofMinutes(1)));
        assertThat(boardEvent(SEPTEMBER_BOARD)).isEmpty();

        runCheckAt(TUESDAY_EVENING);
        assertThat(boardEvent(SEPTEMBER_BOARD)).isPresent();

        // The 1-minute loop keeps running; the Event Log key is the
        // whole timer, so the board never goes out twice.
        runCheckAt(TUESDAY_EVENING.plus(Duration.ofMinutes(1)));
        runCheckAt(TUESDAY_EVENING.plus(Duration.ofHours(2)));
        assertThat(eventLog.all().stream()
                .filter(event -> event.key().startsWith("alert:waiver:"))
                .count()).isEqualTo(1);
        // One Event Log key is the record; one Telegram message is what
        // the user actually gets, and that is the thing being promised.
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void aBoardStillGoesOutLateOnTheTuesdayItIsDue() {
        aWaiverWeekOnDisk();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Five waiver targets for Wednesday.");

        // A source was down at 18:00 and came back at 23:59. The whole
        // evening is still the user's to plan in, so the board goes.
        runCheckAt(LAST_CALL.minus(Duration.ofMinutes(1)));

        assertThat(boardEvent(SEPTEMBER_BOARD)).isPresent();
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void aBoardThatMissedItsEveningIsNeverSentOnWednesday() {
        aWaiverWeekOnDisk();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Five waiver targets for Wednesday.");

        // Midnight, on the nose: it is Wednesday now, and Wednesday is
        // when claims clear. A board here is advice on bids that have
        // already processed, so nothing is sent and nothing is
        // recorded - not at midnight, and not later that day.
        runCheckAt(LAST_CALL);
        assertThat(boardEvent(SEPTEMBER_BOARD)).isEmpty();

        runCheckAt(LAST_CALL.plus(Duration.ofHours(13)));
        assertThat(boardEvent(SEPTEMBER_BOARD)).isEmpty();
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void theEveningHoldsAtSixLocalAcrossTheDaylightSavingBoundary() {
        aWaiverWeekOnDisk();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Five waiver targets for Wednesday.");

        // Summer: 18:00 in Los Angeles is 01:00Z the next day.
        runCheckAt(TUESDAY_EVENING);
        assertThat(boardEvent(SEPTEMBER_BOARD)).isPresent();

        // Winter, after the clocks went back: the same local evening is
        // 02:00Z. An hour before it, nothing is due yet.
        runCheckAt(WINTER_TUESDAY_EVENING.minus(Duration.ofHours(1)));
        assertThat(boardEvent(NOVEMBER_BOARD)).isEmpty();

        runCheckAt(WINTER_TUESDAY_EVENING);
        assertThat(boardEvent(NOVEMBER_BOARD)).isPresent();
    }

    @Test
    void everyBidIsWholeDollarsInsideTheBudgetThatIsActuallyLeft() {
        // $97 of the $100 is already spent. The bands are shares of
        // what is left, so the same board that bid $40-$60 on the
        // breakout now bids $1-$2, and the capped stream rounds to
        // nothing at all. No bid is ever a fraction, below zero, or
        // more than the user can pay.
        SleeperStubs.waiverWeek(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-waivers-budget-spent.json", "rosters-v1");
        NflverseStubs.waiverWeek(nflverse);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Three dollars left.");
        checkRunner.runCheck();
        feeds.updateIfDue();
        defenseBuilder.build();

        runCheckAt(TUESDAY_EVENING);

        Event board = boardEvent(SEPTEMBER_BOARD).orElseThrow();
        assertThat(board.facts()).containsEntry("remainingBudget", "3");
        assertThat(board.facts().get("target1")).contains("Bucky Irving", "bid $1-$2");
        // Every other target rounds to nothing on a $3 budget. All four
        // are pinned, so no stray dollar and no fraction of one can
        // slip through on a target nobody looked at.
        assertThat(board.facts().get("target2")).contains("Wandale Robinson", "bid $0");
        assertThat(board.facts().get("target3")).contains("Cade Otton", "bid $0");
        assertThat(board.facts().get("target4")).contains("Ray Davis", "bid $0");
        assertThat(board.facts().get("target5")).contains("Michael Penix", "bid $0");
        assertThat(board.facts().values()).noneMatch(fact -> fact.contains("bid $0."));
    }

    @Test
    void aBudgetFieldSleeperGarblesStopsTheDocumentRatherThanReadingAsZero() {
        // Money is the one number a silent zero must never stand in
        // for. A budget read as absent would price every bid at $0, and
        // spent FAAB read as zero would offer the user money he no
        // longer has - so a garbled value takes the whole document out
        // and the user hears which feed broke.
        SleeperStubs.waiverWeek(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-waivers-drifted-budget.json", "rosters-v1");
        NflverseStubs.waiverWeek(nflverse);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "A source is down.");

        runCheckAt(TUESDAY_EVENING);

        assertThat(boardEvent(SEPTEMBER_BOARD)).isEmpty();
        assertThat(eventLog.all())
                .anyMatch(event -> event.type() == EventType.SOURCE_UNAVAILABLE
                        && event.key().contains("rosters"));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("waiver_budget_used is not a whole number")));

        // The league's own budget is held to the same rule.
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH,
                "sleeper/league-in-season-drifted-budget.json", "league-v2");
        clock.advance(Duration.ofSeconds(61));
        checkRunner.runCheck();

        assertThat(eventLog.all())
                .anyMatch(event -> event.type() == EventType.SOURCE_UNAVAILABLE
                        && event.facts().getOrDefault("reason", "")
                                .contains("waiver_budget is not a whole number"));
    }

    @Test
    void rankWaiverTargetsAnswersByChatForOnePositionAndAnyCount() {
        aWaiverWeekOnDisk();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, "rank_waiver_targets",
                "{\"position\":\"TE\",\"count\":2}",
                "Cade Otton first, then a filler tight end.");

        ask("best two tight ends on waivers?");

        // The board narrows to tight ends and to two of them, and the
        // scores are the same numbers Tuesday's Alert would have used:
        // one board, whatever asks for it. The 50-point scale still
        // names the best free agent at any position, so a tight end's
        // score does not change because the user asked about tight ends.
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Cade Otton"))
                // The tool answer rides inside the request as a JSON
                // string, so its own quotes arrive escaped.
                .withRequestBody(containing("score\\\":42"))
                .withRequestBody(containing("$5-$10"))
                .withRequestBody(containing("TE replacement level is 5.5"))
                .withRequestBody(containing("scale to Bucky Irving"))
                .withRequestBody(containing("Tight End One"))
                // Two means two, and a tight-end board holds no running
                // backs however well they score on the whole board.
                .withRequestBody(notContaining("Tight End Two"))
                .withRequestBody(notContaining("Ray Davis")));
    }

    @Test
    void twoStraightWeeksOfHalfABackfieldIsABreakoutWithoutAnyDepthChartMove() {
        // The slow lane. Ray Davis stays RB2 behind a healthy James
        // Cook, so the depth chart says nothing and no injury says
        // anything either. Three played weeks do: his share of
        // Buffalo's backfield goes 54%, 58%, 62%, so the last two both
        // clear the 50% that marks a real role. That is a role growing
        // rather than arriving, and it lifts his bid a band from 0-5%
        // to 5-12%. The rise itself is on his line, because a share
        // climbing every week is worth showing whatever the tag.
        aWaiverWeekOnDisk(NflverseStubs::waiverWeekWithAGrowingRole);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, "rank_waiver_targets",
                "{\"position\":\"RB\"}", "Bucky Irving, then Ray Davis.");

        ask("who are the running backs on waivers?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Ray Davis"))
                .withRequestBody(containing("breakout"))
                .withRequestBody(containing("$5-$12"))
                .withRequestBody(containing("he held 58% and then 62% of opportunity share "
                        + "over weeks 2 and 3"))
                .withRequestBody(containing("his share has risen every week: week 1 54%, "
                        + "week 2 58%, week 3 62%"))
                .withRequestBody(containing("raised one band to 5-12% for a breakout")));
    }

    @Test
    void oneWeekAtTheEliteBarIsABreakoutWithNobodyInjuredAnywhere() {
        // The fast lane, in the two shapes that made it necessary.
        //
        // Wandale Robinson is Nacua-shaped: 15 targets of the 39 the
        // Giants threw, 39% of the offence in one game, with nobody
        // hurt ahead of him.
        // Ray Davis is Kyren-shaped: 91% of Buffalo's backfield work in
        // week 1 while James Cook, still RB1 on the chart, is healthy.
        // Neither man is visible to a rule that reads the label on the
        // man ahead, and both are the week's league-winning add. Cade
        // Otton at 32% of Tampa Bay's targets is the same claim at
        // tight end, so all three positions the lanes cover are here.
        aWaiverWeekOnDisk(NflverseStubs::waiverWeekWithEarnedRoles);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Three men took their own jobs.");

        runCheckAt(TUESDAY_EVENING);

        Event board = boardEvent(SEPTEMBER_BOARD).orElseThrow();
        assertThat(targetLine(board, "Wandale Robinson"))
                .contains("breakout", "he took 39% of target share in week 1");
        assertThat(targetLine(board, "Cade Otton"))
                .contains("breakout", "he took 32% of target share in week 1");
        assertThat(targetLine(board, "Ray Davis"))
                .contains("breakout", "he took 91% of opportunity share in week 1");
    }

    @Test
    void aGrowingShareTagsOnlyTheManWhoHeldItInStraightWeeksUpToNow() {
        // Three weeks on record, and four free agents whose shares say
        // four different things.
        //
        // Cade Otton held 21% and then 24% of Tampa Bay's targets in
        // weeks 2 and 3: the slow lane at tight end. Bucky Irving took
        // 71% of the backfield in week 3, which is the fast lane, and
        // it fires even though Rachaad White ahead of him is only
        // designated to return - a loan the man himself has outgrown.
        //
        // The other two must stay quiet. Wandale Robinson cleared 18%
        // in weeks 1 and 3 but did not play week 2, and two games with
        // a gap between them are not two straight weeks. Ray Davis owned
        // 95% of Buffalo's backfield in weeks 1 and 2 and has not played
        // since, so his claim is about a role he held a fortnight ago.
        aWaiverWeekOnDisk(NflverseStubs::waiverWeekWithGrowingAndStaleShares);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "One grew into it, one outgrew a loan.");

        runCheckAt(TUESDAY_EVENING);

        Event board = boardEvent(SEPTEMBER_BOARD).orElseThrow();
        assertThat(targetLine(board, "Cade Otton"))
                .contains("breakout",
                        "he held 21% and then 24% of target share over weeks 2 and 3");
        assertThat(targetLine(board, "Bucky Irving"))
                .contains("breakout",
                        "Rachaad White is not on a list that ends his season",
                        "he took 71% of opportunity share in week 3");
        assertThat(targetLine(board, "Wandale Robinson")).doesNotContain("breakout");
        assertThat(targetLine(board, "Ray Davis")).doesNotContain("breakout");
    }

    @Test
    void withNoRosterStandingsTheBoardSaysSoRatherThanGuessingEitherWay() {
        // The weekly-roster feed is down, so nothing is known about
        // whether Rachaad White comes back. That is not the same as
        // knowing he does, and it is not the same as knowing he does
        // not: the board must claim neither. Bucky Irving keeps his 20
        // usage points, because White cannot play this Sunday either
        // way, and loses only the breakout the standing would have
        // justified.
        aWaiverWeekOnDisk(NflverseStubs::waiverWeekWithNoRosterStandings);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "One feed is down.");

        runCheckAt(TUESDAY_EVENING);

        Event board = boardEvent(SEPTEMBER_BOARD).orElseThrow();
        assertThat(targetLine(board, "Bucky Irving"))
                .contains("Rachaad White, ahead of him on that chart, is IR")
                .contains("I cannot see Rachaad White's roster standing")
                .doesNotContain("breakout")
                .doesNotContain("is not on a list that ends his season");
    }

    @Test
    void aStarterWhoIsDesignatedToReturnLendsTheRoleRatherThanLosingIt() {
        // Bucky Irving's whole case is Rachaad White's absence: he has
        // no stat line of his own on this board. When White is on
        // injured reserve with no way back, the job is Irving's and the
        // bid is a breakout's. When White is designated to return, the
        // same chart move is a loan of four games, and an aggressive
        // bid on a loan is how a budget disappears.
        aWaiverWeekOnDisk(NflverseStubs::waiverWeekWithAReturningStarter);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "One loan and no breakout.");

        runCheckAt(TUESDAY_EVENING);

        Event board = boardEvent(SEPTEMBER_BOARD).orElseThrow();
        assertThat(targetLine(board, "Bucky Irving"))
                .contains("Rachaad White is not on a list that ends his season")
                .doesNotContain("breakout");
    }

    @Test
    void aShareFromLastSeasonIsNeverThisWeeksBreakout() {
        // Before week 1 is played the stats feed is last season's final
        // record, on purpose: a defence table has to say something in
        // week 1 and last season is the honest thing to say. A breakout
        // is the opposite case. "He took 91% of the backfield" is news
        // about a role now, and last December is not now - so the lanes
        // read nothing at all until a week of this season is played,
        // and the board says which week it is short of.
        SleeperStubs.waiverWeek(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.STATE_PATH,
                "sleeper/state-nfl-week1.json", "state-week1");
        SleeperStubs.stubJson(sleeper, "/v1/projections/nfl/regular/2026/1",
                "sleeper/projections-waivers.json", "projections-week1");
        SleeperStubs.stubJson(sleeper, "/scores/nfl/regular/2026/1",
                "sleeper/scores-2026-2.json", "scores-week1");
        NflverseStubs.waiverWeekBeforeAnyGameIsPlayed(nflverse);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Nothing has been played yet.");
        checkRunner.runCheck();
        feeds.updateIfDue();
        defenseBuilder.build();

        runCheckAt(TUESDAY_EVENING);

        Event board = boardEvent(SEPTEMBER_BOARD).orElseThrow();
        assertThat(targetLine(board, "Wandale Robinson"))
                .doesNotContain("breakout")
                .doesNotContain("target share");

        // Last season's file was read and the current season's was
        // never asked for, which is what makes the assertion above a
        // test of the gate rather than of an empty feed: Wandale
        // Robinson holds a 39% share inside the file that was read.
        nflverse.verify(1, getRequestedFor(urlEqualTo(NflverseStubs.STATS_2025_PATH)));
        nflverse.verify(0, getRequestedFor(urlEqualTo(NflverseStubs.STATS_2026_PATH)));

        // And the board says what it is short of, rather than going quiet.
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("no week of this season has been played yet")));
    }

    /** The board's own line for one player, whatever rank he landed at. */
    private static String targetLine(Event board, String player) {
        return board.facts().entrySet().stream()
                .filter(fact -> fact.getKey().startsWith("target"))
                .map(Map.Entry::getValue)
                .filter(fact -> fact.contains(player))
                .findFirst()
                .orElseThrow(() -> new AssertionError(player + " is not on the board"));
    }

    @Test
    void mutingTheBoardSilencesWaiverAlertsAndNothingElse() {
        aWaiverWeekOnDisk();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.telegramCallbackAnswered(telegram);
        OutboundStubs.llmPhrases(llm, "Five waiver targets for Wednesday.");

        runCheckAt(TUESDAY_EVENING);
        long alertId = Long.parseLong(boardEvent(SEPTEMBER_BOARD).orElseThrow()
                .facts().get("alertId"));

        assertThat(webhook.handle(WEBHOOK_SECRET,
                OutboundStubs.callbackTap("mute:" + alertId))).isEqualTo(WebhookResult.OK);

        // The Mute button under a waiver board silences waiver boards.
        // It must never fall through to another class - the user would
        // lose the illegal-lineup Alerts he never touched.
        assertThat(muteStore.muted("class:waiver")).isTrue();
        assertThat(muteStore.muted("class:legality")).isFalse();

        // Next Tuesday's board is computed and withheld.
        telegram.resetRequests();
        runCheckAt(WINTER_TUESDAY_EVENING);
        assertThat(boardEvent(NOVEMBER_BOARD)).isEmpty();
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void switchingTheWaiverTriggerOffStopsTheBoardBeingBuiltAtAll() {
        aWaiverWeekOnDisk();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Five waiver targets for Wednesday.");
        settings.change(current -> current.withTrigger(Trigger.WAIVER, false));

        runCheckAt(TUESDAY_EVENING);

        // Off means off: no board, no Event, and no news feeds read.
        assertThat(boardEvent(SEPTEMBER_BOARD)).isEmpty();
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        sleeper.verify(0, getRequestedFor(urlPathMatching("/players/nfl/[0-9]+/news")));

        // Switched back on, the next Tuesday evening board goes out.
        settings.change(current -> current.withTrigger(Trigger.WAIVER, true));
        runCheckAt(WINTER_TUESDAY_EVENING);
        assertThat(boardEvent(NOVEMBER_BOARD)).isPresent();
    }

    @Test
    void anUnknownPositionIsRefusedByNameRatherThanAnswered() {
        aWaiverWeekOnDisk();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, "rank_waiver_targets",
                "{\"position\":\"K\"}",
                "I do not rank kickers - this league has no kicker slot.");

        ask("best kickers on waivers?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("I rank waiver targets at QB, RB, WR and TE")));
    }
}
