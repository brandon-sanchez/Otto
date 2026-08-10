package otto;

import java.time.Duration;
import java.time.Instant;
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
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
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
 * adds five points to both ends: 10-15% of $100.</li>
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
    void twoWeeksOfTopOfPositionWorkloadIsABreakoutWithoutAnyDepthChartMove() {
        // Ray Davis stays RB2 behind a healthy James Cook, so the
        // depth chart says nothing. Two played weeks do: he leads every
        // running back in the league in carries plus targets, and
        // Miami, who he faced, give up less to running backs than the
        // average defence - so the workload is his role, not his
        // matchup. That is the other route to a breakout, and it lifts
        // his bid a band from 0-5% to 5-12%.
        aWaiverWeekOnDisk(NflverseStubs::waiverWeekWithTwoPlayedWeeks);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, "rank_waiver_targets",
                "{\"position\":\"RB\"}", "Bucky Irving, then Ray Davis.");

        ask("who are the running backs on waivers?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Ray Davis"))
                .withRequestBody(containing("breakout"))
                .withRequestBody(containing("$5-$12"))
                .withRequestBody(containing("raised one band to 5-12% for a breakout")));
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
    void aBoardPricesEveryCandidateAgainstOneBenchPlayerTheUserWouldDrop() {
        // Josh Jacobs projects 12.5 on the bench. Four free agents beat
        // him and one does not, and the one who does not is still on the
        // board - ranked below every candidate who beats him, and saying
        // so on his own line. "Nobody out there is better than what you
        // have" is an answer; an empty list reads like a broken feed.
        aWaiverWeekOnDisk();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, "rank_waiver_targets",
                "{\"replacing\":[\"Josh Jacobs\"]}",
                "Bucky Irving is 6.5 points a week better than Josh Jacobs.");

        ask("is anybody on waivers better than Josh Jacobs?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(
                        "against Josh Jacobs he is +6.5 points a week: 19.0 projected "
                                + "against 12.5"))
                .withRequestBody(containing(
                        "against Josh Jacobs he is +8.1 points a week: 20.6 projected "
                                + "against 12.5"))
                // Ray Davis projects 7.5 and beats nobody named, so he
                // says so and drops behind Michael Penix - who scores a
                // point less than him on the board they share.
                .withRequestBody(containing(
                        "against Josh Jacobs he is -5.0 points a week: 7.5 projected "
                                + "against 12.5"))
                .withRequestBody(containing("he out-projects none of Josh Jacobs, so he is "
                        + "ranked below every candidate who beats one of them"))
                .withRequestBody(containing("ranked below every candidate who beats at least "
                        + "one, and his line says so"))
                .withRequestBody(matching("(?s).*Michael Penix.*Ray Davis.*"))
                // The gains ride as structured fields as well as words,
                // and the flag that drove the ordering is on the line.
                .withRequestBody(containing("gains\\\":[{"))
                .withRequestBody(containing("theirProjection\\\":\\\"12.5\\\""))
                .withRequestBody(containing("gain\\\":\\\"+6.5\\\""))
                .withRequestBody(containing("beatsSomebodyNamed\\\":true"))
                .withRequestBody(containing("beatsSomebodyNamed\\\":false"))
                // The 50-point scale is the whole board's, not the
                // beaters'. Bucky Irving still takes all 50 and scores
                // 100, the number the Tuesday Alert gave him.
                .withRequestBody(containing("score\\\":100"))
                .withRequestBody(containing("scale to Bucky Irving")));
    }

    @Test
    void aBoardWhereNobodyBeatsTheDropSaysSoFirstAndSaysByHowMuch() {
        // Patrick Mahomes projects 22.0 and no free agent reaches him.
        // The user asked to be told that in words rather than left to
        // infer it from an ordering, so the board leads with it, names
        // the closest and says how far short he fell. The ranking still
        // follows: he may want to see who came nearest.
        aWaiverWeekOnDisk();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, "rank_waiver_targets",
                "{\"replacing\":[\"Patrick Mahomes\"]}",
                "Nobody out there beats Mahomes. Stand pat.");

        ask("is anybody on waivers better than Mahomes?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Nobody on waivers out-projects Patrick Mahomes this "
                        + "week. The closest is Michael Penix, who projects 20.6 against the "
                        + "22.0 Patrick Mahomes projects, so he is 1.4 short. Keep who you have."))
                // It is the answer, not a footnote on one: it rides in
                // its own field, ahead of the ranking.
                .withRequestBody(containing("answer\\\":\\\"Nobody on waivers out-projects"))
                // And the board is still a board underneath it.
                .withRequestBody(containing("Bucky Irving"))
                .withRequestBody(containing("Michael Penix"))
                .withRequestBody(containing("beatsSomebodyNamed\\\":false"))
                .withRequestBody(notContaining("beatsSomebodyNamed\\\":true")));
    }

    @Test
    void twoReferencesToTheSamePlayerArePricedAsOneDrop() {
        // The user can only drop him once, so naming him by name and
        // again by Sleeper id must not double his line or make the swap
        // look bigger than it is.
        aWaiverWeekOnDisk();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, "rank_waiver_targets",
                "{\"replacing\":[\"Josh Jacobs\",\"5850\"]}", "One drop, not two.");

        ask("who beats Josh Jacobs?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Josh Jacobs (RB, 12.5 projected)"))
                .withRequestBody(notMatching("(?s).*Josh Jacobs \\(RB, 12\\.5 projected\\)"
                        + ".*Josh Jacobs \\(RB, 12\\.5 projected\\).*")));
    }

    @Test
    void aDropWithNoProjectionIsSaidRatherThanPricedAtZero() {
        // The player a user most wants to drop is the one with no stat
        // line this week. Reading that as zero would price every
        // candidate on the board as a large upgrade over him, so the
        // gain is left out and the board says which projection it does
        // not have.
        SleeperStubs.waiverWeek(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH,
                "sleeper/projections-waivers-jacobs-unpriced.json", "projections-v1");
        NflverseStubs.waiverWeek(nflverse);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "A lineup alert.");
        checkRunner.runCheck();
        feeds.updateIfDue();
        defenseBuilder.build();
        llm.resetAll();

        OutboundStubs.llmCallsToolThenPhrases(llm, "rank_waiver_targets",
                "{\"replacing\":[\"Josh Jacobs\"]}", "I cannot price Josh Jacobs this week.");

        ask("who beats Josh Jacobs?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(
                        "I have no projection available for Josh Jacobs, so no candidate "
                                + "carries a measured gain over him"))
                // The sentence names the side that is missing, and only
                // that side: the candidate's own projection is fine.
                .withRequestBody(containing("I have no projection available for Josh Jacobs this "
                        + "week, so I cannot say what you gain over him"))
                // The board is a real board, not an empty one: Bucky
                // Irving is still on it and still scores 100.
                .withRequestBody(containing("Bucky Irving"))
                .withRequestBody(containing("score\\\":100"))
                // No gain is printed, and nobody is ranked below anybody
                // for losing to a player nothing can be measured against.
                .withRequestBody(notContaining("gain\\\":"))
                .withRequestBody(notContaining("beatsSomebodyNamed\\\":false")));
    }

    @Test
    void aBoardPricesEveryCandidateAgainstEveryPlayerTheUserWouldDrop() {
        // Two names, two gains on every line. Dallas Goedert projects
        // 10.5 and Josh Jacobs 12.5, so a candidate is measured against
        // both rather than against whichever the board picked.
        aWaiverWeekOnDisk();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, "rank_waiver_targets",
                "{\"replacing\":[\"Josh Jacobs\",\"Dallas Goedert\"]}",
                "Bucky Irving beats both of them.");

        ask("who on waivers beats Josh Jacobs or Dallas Goedert?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(
                        "against Josh Jacobs he is +6.5 points a week: 19.0 projected "
                                + "against 12.5"))
                .withRequestBody(containing(
                        "against Dallas Goedert he is +8.5 points a week: 19.0 projected "
                                + "against 10.5"))
                // Ray Davis at 7.5 is under both of them.
                .withRequestBody(containing("he out-projects none of Josh Jacobs and Dallas "
                        + "Goedert, so he is ranked below every candidate who beats one of them"))
                .withRequestBody(containing(
                        "I priced every candidate against Josh Jacobs and Dallas Goedert")));
    }

    @Test
    void aPlayerTheUserDoesNotRosterIsRefusedByNameRatherThanIgnored() {
        // Saquon Barkley is real and is somebody else's. Pricing the
        // board against nobody would read exactly like pricing it
        // against the man the user meant, so it is refused by name - and
        // "he is not yours" is kept apart from "I have never heard of
        // him", because the two have different fixes.
        aWaiverWeekOnDisk();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, "rank_waiver_targets",
                "{\"replacing\":[\"Saquon Barkley\"]}",
                "Saquon Barkley is not on your roster.");

        ask("anybody better than Saquon Barkley on waivers?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Saquon Barkley is not on your roster, so you "
                        + "cannot drop him"))
                .withRequestBody(notContaining("Bucky Irving")));

        llm.resetAll();
        OutboundStubs.llmCallsToolThenPhrases(llm, "rank_waiver_targets",
                "{\"replacing\":[\"Elmer Fudd\"]}",
                "I do not know an Elmer Fudd.");

        ask("anybody better than Elmer Fudd?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                // The name is quoted in the refusal, so it arrives with
                // a JSON escape on either side of it.
                .withRequestBody(matching(
                        "(?s).*nobody on your roster matches [^E]*Elmer Fudd[^,]*, so I cannot "
                                + "price a pickup against him.*"))
                // A refusal is a refusal: no board rides with it, so the
                // model has no ranking it could narrate past the error.
                .withRequestBody(notContaining("Bucky Irving"))
                .withRequestBody(notContaining("candidates\\\":")));
    }

    @Test
    void theSlotBumpSurvivesDroppingABenchPlayerAndDiesDroppingAStarter() {
        // Wandale Robinson fills the receiver slot Puka Nacua's bye
        // leaves illegal, which is worth five points on both ends of the
        // bid. Dropping Josh Jacobs off the bench costs nothing, so the
        // bump stands: 10-15% of $100.
        aWaiverWeekOnDisk();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, "rank_waiver_targets",
                "{\"replacing\":[\"Josh Jacobs\"]}", "Bid $10-$15 on Wandale Robinson.");

        ask("who do I pick up for Josh Jacobs?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Wandale Robinson"))
                .withRequestBody(containing("$10-$15"))
                .withRequestBody(containing("fills a slot you cannot legally fill this week")));

        // Justin Jefferson is a healthy starter. Dropping him empties a
        // slot the user can fill today, so the swap moves the hole
        // rather than plugging one and the bump does not fire: back to
        // the capped 5-10% of a one-week stream.
        llm.resetAll();
        OutboundStubs.llmCallsToolThenPhrases(llm, "rank_waiver_targets",
                "{\"replacing\":[\"Justin Jefferson\"]}", "Nobody is worth that.");

        ask("who do I pick up for Justin Jefferson?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("$5-$10"))
                .withRequestBody(containing("dropping Justin Jefferson empties a starting slot "
                        + "you can legally fill today"))
                .withRequestBody(notContaining("fills a slot you cannot legally fill this week"))
                // Jefferson projects 19.0, and only Michael Penix beats
                // him. The best free agent on the board scores 100 and
                // still ranks below a 20 - because the user asked who
                // beats Jefferson, not who is best.
                .withRequestBody(matching("(?s).*Michael Penix.*Bucky Irving.*")));
    }

    @Test
    void needsOnlyKeepsTheBoardToThePositionsTheUserIsShortAt() {
        // The user has a bench running back and a bench tight end above
        // replacement, and neither a bench receiver nor a third
        // quarterback. So the needs are QB and WR, and the board holds
        // those two alone.
        aWaiverWeekOnDisk();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, "rank_waiver_targets",
                "{\"needsOnly\":true}", "You need a receiver and a quarterback.");

        ask("where do I need help on waivers?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("I kept the board to QB and WR"))
                .withRequestBody(containing("Wandale Robinson"))
                .withRequestBody(containing("Michael Penix"))
                // The 1.1 multiplier is visible on the line it lifted,
                // not just inside the number.
                .withRequestBody(containing("you have no bench WR above replacement, so the "
                        + "score carries the 1.1 roster-need multiplier"))
                // Narrowing the question moves no number. Wandale scores
                // the 57 the whole-position board gave him, and the
                // 50-point scale still names Bucky Irving, who is a
                // running back and is not on this board at all.
                .withRequestBody(containing("score\\\":57"))
                .withRequestBody(containing("scale to Bucky Irving"))
                .withRequestBody(notContaining("Bucky Irving\\\","))
                .withRequestBody(notContaining("Cade Otton"))
                .withRequestBody(notContaining("Ray Davis")));
    }

    @Test
    void needsOnlyWithNoNeedAnywhereSaysSoRatherThanReturningAnEmptyList() {
        // The same week, with a third quarterback and a bench receiver
        // above replacement added to the user's roster. Every position
        // now has an answer, so the honest reply is that there is no
        // need - not a list of nobody.
        SleeperStubs.waiverWeek(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-waivers-no-needs.json", "rosters-v1");
        NflverseStubs.waiverWeek(nflverse);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "A lineup alert.");
        checkRunner.runCheck();
        feeds.updateIfDue();
        defenseBuilder.build();
        llm.resetAll();

        OutboundStubs.llmCallsToolThenPhrases(llm, "rank_waiver_targets",
                "{\"needsOnly\":true}", "You have an answer everywhere.");

        ask("where do I need help on waivers?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                // Said out loud and said first, in the same voice the
                // board uses when nobody beats the man he would drop.
                .withRequestBody(containing("answer\\\":\\\"You are short at nothing this week."))
                .withRequestBody(containing("there is no position I would call a need and "
                        + "nothing on this board. Stand pat."))
                // Empty means empty, and the board says so as a board
                // rather than as a list somebody forgot to fill.
                .withRequestBody(containing("candidates\\\":[]"))
                .withRequestBody(notContaining("Wandale Robinson"))
                .withRequestBody(notContaining("Bucky Irving\\\",")));
    }

    @Test
    void needsOnlyAtAPositionTheUserIsNotShortAtSaysWhichPositionsHeIs() {
        // Tight end is covered, so a needs board about tight ends holds
        // nobody. The honest reply names the positions he is short at
        // rather than handing back an empty list with no reason on it.
        aWaiverWeekOnDisk();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, "rank_waiver_targets",
                "{\"position\":\"TE\",\"needsOnly\":true}", "You are covered at tight end.");

        ask("do I need a tight end off waivers?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("answer\\\":\\\"You are not short at TE this week, so "
                        + "there is nothing on this board. Where you are short is QB and WR. "
                        + "Stand pat at TE."))
                .withRequestBody(containing("candidates\\\":[]"))
                .withRequestBody(notContaining("Cade Otton")));
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
