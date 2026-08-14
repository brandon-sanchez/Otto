package otto;

import java.time.Duration;
import java.time.Instant;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import otto.alerts.MuteStore;
import otto.check.CheckRunner;
import otto.events.EventLog;
import otto.events.EventType;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.sleeper.SleeperAdapter;
import otto.telegram.TelegramWebhook;
import otto.telegram.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * League awareness: the user can Ask for the standings, his playoff
 * seed and the clinch or elimination picture, and for a league mate's
 * roster with its strengths and gaps.
 *
 * The scenario runs the league in week 14, the last week of its regular
 * season, so the playoff math has one game left to reason about. Twelve
 * teams carry a record; the fixture fills the rosters of three of them -
 * the user's, GridironGoblin's and WaiverWizard's - because standings
 * need records and nothing else.
 *
 * The weekly projection table is deep enough to rank: every position
 * carries more players than its Notable Player cutoff, and the
 * synthetic ones are named for the rank they hold, so "TE Depth 13" is
 * the 13th tight end and sits outside the top-12 cutoff.
 */
class LeagueAwarenessScenarioTest extends WireSeamTest {

    /** The Tuesday of week 14, before any game of that week locks. */
    private static final Instant WEEK_14 = Instant.parse("2026-12-08T18:00:00Z");

    private static final String PROJECTIONS_PATH = "/v1/projections/nfl/regular/2026/14";
    private static final String SCORES_PATH = "/scores/nfl/regular/2026/14";
    private static final String TRANSACTIONS_PATH =
            SleeperStubs.LEAGUE_PATH + "/transactions/14";

    /** A Check reads the week before as well, so a late trade stays in view. */
    private static final String LAST_WEEKS_TRANSACTIONS_PATH =
            SleeperStubs.LEAGUE_PATH + "/transactions/13";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private TelegramWebhook webhook;

    @Autowired
    private EventLog eventLog;

    @Autowired
    private MuteStore muteStore;

    private ListAppender<ILoggingEvent> adapterAppender;

    /** A quiet week-14 league with twelve teams and full standings. */
    private void week14League() {
        clock.set(WEEK_14);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-league.json", "players-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH,
                "sleeper/league-in-season.json", "league-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-league.json", "rosters-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.USERS_PATH,
                "sleeper/users-league.json", "users-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.STATE_PATH,
                "sleeper/state-nfl-week14.json", "state-v1");
        SleeperStubs.stubJson(sleeper, PROJECTIONS_PATH,
                "sleeper/projections-league.json", "projections-v1");
        SleeperStubs.stubJson(sleeper, SCORES_PATH, "sleeper/scores-2026-14.json", "scores-v1");
        SleeperStubs.stubJson(sleeper, TRANSACTIONS_PATH,
                "sleeper/transactions-none.json", "transactions-v1");
        SleeperStubs.stubJson(sleeper, LAST_WEEKS_TRANSACTIONS_PATH,
                "sleeper/transactions-none.json", "transactions-w13");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "A quiet week.");
    }

    /** Ask tools read the stored Snapshot, so a Check builds one first. */
    private void snapshotOfTheLeague() {
        week14League();
        checkRunner.runCheck();
        llm.resetAll();
        telegram.resetRequests();
    }

    private void ask(String text) {
        assertThat(webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage(text)))
                .isEqualTo(WebhookResult.OK);
    }

    /** The next Check a minute later, with every feed answering 304. */
    private void nextCheckWithNothingElseChanged() {
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.PLAYERS_PATH, "players-v1");
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.LEAGUE_PATH, "league-v1");
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.ROSTERS_PATH, "rosters-v1");
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.USERS_PATH, "users-v1");
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.STATE_PATH, "state-v1");
        SleeperStubs.stubNotModified(sleeper, PROJECTIONS_PATH, "projections-v1");
        SleeperStubs.stubNotModified(sleeper, SCORES_PATH, "scores-v1");
        SleeperStubs.stubNotModified(sleeper, TRANSACTIONS_PATH, "transactions-v1");
        SleeperStubs.stubNotModified(sleeper, LAST_WEEKS_TRANSACTIONS_PATH, "transactions-w13");
    }

    @Test
    void standingsCarryEveryTeamAndTheUsersPlayoffSeed() {
        snapshotOfTheLeague();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_standings", "{}",
                "You are the 3 seed at 9-4.");

        ask("where do I sit in the standings?");

        // Wins first, points for as the tiebreaker: GridironGoblin at
        // 10-3 leads, and the user is third on 9-4 behind WaiverWizard's
        // higher points for.
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("GridironGoblin"))
                .withRequestBody(containing("WaiverWizard"))
                .withRequestBody(containing("TwoPointTina"))
                .withRequestBody(containing("9-4"))
                .withRequestBody(containing("1450.5"))
                .withRequestBody(containing("\\\"yourSeed\\\":3")));
    }

    @Test
    void thePlayoffRaceCountsClinchAndEliminationWithoutSimulating() {
        snapshotOfTheLeague();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, "analyze_playoff_race", "{}",
                "You are in: your seed is safe with a week to play.");

        ask("am I in the playoffs yet?");

        // One game left, six spots. At 9-4 only four teams can still
        // reach nine wins, so the user is in whatever happens. Four
        // teams cannot reach the sixth seed's win total and are out.
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("\\\"gamesRemaining\\\":1"))
                .withRequestBody(containing("\\\"yourStatus\\\":\\\"clinched\\\""))
                .withRequestBody(containing("BlitzBrigade"))
                .withRequestBody(containing("eliminated")));
    }

    @Test
    void aTieCountsAsHalfAWinInTheClinchAndEliminationMath() {
        week14League();
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-league-ties.json", "rosters-v1");
        checkRunner.runCheck();
        llm.resetAll();
        telegram.resetRequests();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, "analyze_playoff_race", "{}",
                "You are still alive: win and the three ties carry you.");

        ask("am I out of it?");

        // The user is 6-4-3 with six teams on 8-5 above him. Counting
        // wins alone he is a win short of all six and looks eliminated;
        // his three ties are worth another win and a half, so winning
        // his last game puts him above every one of them.
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("\\\"yourStatus\\\":\\\"in the hunt\\\""))
                .withRequestBody(containing("6-4-3")));
    }

    @Test
    void aLeagueMatesRosterComesBackWithItsStrengthsAndGaps() {
        snapshotOfTheLeague();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_team_roster",
                "{\"manager\":\"GridironGoblin\"}",
                "GridironGoblin is deep at receiver and has nothing at tight end.");

        ask("what does GridironGoblin have?");

        // Their tight end starts below the TE replacement level and no
        // tight end sits on their bench, while four of their receivers
        // beat the WR replacement level.
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("GridironGoblin"))
                .withRequestBody(containing("Saquon Barkley"))
                .withRequestBody(containing("TE Depth 13"))
                .withRequestBody(containing("below the TE replacement level"))
                .withRequestBody(containing("Deep at WR")));
    }

    @Test
    void anyTradeInTheLeagueSendsAnInformationalAlert() {
        week14League();
        checkRunner.runCheck();
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));

        nextCheckWithNothingElseChanged();
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-league-after-trade.json", "rosters-v2");
        SleeperStubs.stubJson(sleeper, TRANSACTIONS_PATH,
                "sleeper/transactions-week14-trade.json", "transactions-v2");
        checkRunner.runCheck();

        // Two teams the user is not in still matter: he asked to hear
        // about every trade in the league.
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.contains("alert:trade:1210000000000000001")).isTrue();
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("GridironGoblin"))
                .withRequestBody(containing("WaiverWizard"))
                .withRequestBody(containing("Saquon Barkley"))
                .withRequestBody(containing("RB Depth 10")));

        // The trade is news once: the next Check says nothing about it.
        nextCheckWithNothingElseChanged();
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.ROSTERS_PATH, "rosters-v2");
        SleeperStubs.stubNotModified(sleeper, TRANSACTIONS_PATH, "transactions-v2");
        checkRunner.runCheck();
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    /**
     * The Alert retry window is measured from the time a transaction
     * completed, so a transaction Otto cannot time is one it cannot
     * place in or out of that window. It says so rather than guessing
     * with its own clock, which would stamp week-old news as fresh.
     */
    @Test
    void aTransactionWithNoUsableTimestampIsSaidPlainlyRatherThanGuessed() {
        week14League();
        checkRunner.runCheck();

        nextCheckWithNothingElseChanged();
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-league-after-trade.json", "rosters-v2");
        SleeperStubs.stubJson(sleeper, TRANSACTIONS_PATH,
                "sleeper/transactions-week14-trade-drifted.json", "transactions-v2");
        checkRunner.runCheck();

        assertThat(eventLog.contains("alert:trade:1210000000000000001")).isFalse();
        assertThat(eventLog.all()).anyMatch(event ->
                event.type() == EventType.SOURCE_UNAVAILABLE
                        && event.key().contains("transactions/14")
                        && event.facts().get("reason").contains("status_updated"));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("transactions")));
    }

    @Test
    void aMutedTradeClassStaysQuiet() {
        week14League();
        checkRunner.runCheck();
        muteStore.mute("class:trade", clock.instant());

        nextCheckWithNothingElseChanged();
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-league-after-trade.json", "rosters-v2");
        SleeperStubs.stubJson(sleeper, TRANSACTIONS_PATH,
                "sleeper/transactions-week14-trade.json", "transactions-v2");
        checkRunner.runCheck();

        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.contains("alert:trade:1210000000000000001")).isFalse();
    }

    /** GridironGoblin drops Saquon Barkley and TE Depth 13 at once. */
    private void checkAfterTheDrops() {
        nextCheckWithNothingElseChanged();
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-league-after-drops.json", "rosters-v2");
        SleeperStubs.stubJson(sleeper, TRANSACTIONS_PATH,
                "sleeper/transactions-week14-drops.json", "transactions-v2");
        checkRunner.runCheck();
    }

    @Test
    void aDroppedNotablePlayerAlertsAndADepthPlayerDoesNot() {
        week14League();
        checkRunner.runCheck();

        checkAfterTheDrops();

        // Barkley is the best running back on the week's board, so his
        // drop is news. TE Depth 13 sits outside the top-12 tight ends:
        // the gate holds that one at Low, which is Ask only.
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.contains("alert:snapshot-diff:drop:1210000000000000002:4866"))
                .isTrue();
        assertThat(eventLog.contains("alert:snapshot-diff:drop:1210000000000000003:94013"))
                .isFalse();
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Saquon Barkley"))
                .withRequestBody(containing("Consider a claim"))
                .withRequestBody(containing("MEDIUM"))
                .withRequestBody(containing("GridironGoblin")));
    }

    @Test
    void aPlayerTheUserDroppedHimselfIsNotNewsToHim() {
        week14League();
        checkRunner.runCheck();

        // He cuts Dallas Goedert off his own bench, a top-12 tight end.
        nextCheckWithNothingElseChanged();
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-league-mine-dropped.json", "rosters-v2");
        SleeperStubs.stubJson(sleeper, TRANSACTIONS_PATH,
                "sleeper/transactions-week14-drop-mine.json", "transactions-v2");
        checkRunner.runCheck();

        // Notable, but his own move: advising a claim on the man he just
        // cut would be absurd, so the drop stays quiet.
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.contains("alert:snapshot-diff:drop:1210000000000000004:6813"))
                .isFalse();
    }

    /**
     * A Commissioner Edit takes a player from one roster and puts him
     * on another without either manager agreeing to anything. Sleeper
     * does not document the type, but it publishes it, and a player
     * leaving the user's roster is the loudest news the league can
     * make.
     */
    @Test
    void aCommissionerEditOffTheUsersRosterIsNews() {
        week14League();
        checkRunner.runCheck();

        // The commissioner takes Josh Jacobs off the user's bench and
        // puts him on GridironGoblin's roster.
        nextCheckWithNothingElseChanged();
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-league-after-commissioner-edit.json", "rosters-v2");
        SleeperStubs.stubJson(sleeper, TRANSACTIONS_PATH,
                "sleeper/transactions-week14-commissioner-edit.json", "transactions-v2");
        checkRunner.runCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.contains("alert:commissioner:1210000000000000011")).isTrue();
        // It is a fact, so it rides at High and states itself - and it
        // is not called a trade, because nobody traded anything.
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Josh Jacobs"))
                .withRequestBody(containing("SenorMustache"))
                .withRequestBody(containing("GridironGoblin"))
                .withRequestBody(containing("Commissioner edit"))
                .withRequestBody(containing("HIGH")));

        // News once: the next Check says nothing about it.
        nextCheckWithNothingElseChanged();
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.ROSTERS_PATH, "rosters-v2");
        SleeperStubs.stubNotModified(sleeper, TRANSACTIONS_PATH, "transactions-v2");
        checkRunner.runCheck();
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    /**
     * A commissioner drop elsewhere in the league is a drop like any
     * other: the Notable Player rule decides whether the user hears it.
     */
    @Test
    void aCommissionerDropFollowsTheNotablePlayerRule() {
        week14League();
        checkRunner.runCheck();

        nextCheckWithNothingElseChanged();
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-league-after-commissioner-drop.json", "rosters-v2");
        SleeperStubs.stubJson(sleeper, TRANSACTIONS_PATH,
                "sleeper/transactions-week14-commissioner-drop.json", "transactions-v2");
        checkRunner.runCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.contains("alert:snapshot-diff:drop:1210000000000000012:4866"))
                .isTrue();
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Saquon Barkley"))
                .withRequestBody(containing("Consider a claim"))
                .withRequestBody(containing("MEDIUM")));
    }

    /**
     * The same edit with nobody on the other end: the commissioner cuts
     * a player off the user's roster. It reads in roster state exactly
     * like a cut the user made himself, which is the one the assistant
     * stays quiet about - so the event has to say who made it.
     */
    @Test
    void aCommissionerCutOffTheUsersRosterIsNewsThoughHisOwnCutIsNot() {
        week14League();
        checkRunner.runCheck();

        nextCheckWithNothingElseChanged();
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-league-mine-dropped.json", "rosters-v2");
        SleeperStubs.stubJson(sleeper, TRANSACTIONS_PATH,
                "sleeper/transactions-week14-commissioner-cut-mine.json", "transactions-v2");
        checkRunner.runCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.contains("alert:snapshot-diff:drop:1210000000000000016:6813"))
                .isTrue();
        // He is short a tight end whatever the projection table thinks
        // of the man: the message states the loss, it does not weigh it.
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Dallas Goedert"))
                .withRequestBody(containing("off your roster"))
                .withRequestBody(containing("HIGH")));
    }

    /**
     * A commissioner add from free agency is a claim like any other:
     * one add event, which is what a Watchlist Snipe reads. Nobody is
     * watching Puka Nacua here, so nothing is sent.
     */
    @Test
    void aCommissionerAddFromFreeAgencyIsAnAddLikeAnyOther() {
        week14League();
        checkRunner.runCheck();

        nextCheckWithNothingElseChanged();
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-league-after-commissioner-add.json", "rosters-v2");
        SleeperStubs.stubJson(sleeper, TRANSACTIONS_PATH,
                "sleeper/transactions-week14-commissioner-add.json", "transactions-v2");
        checkRunner.runCheck();

        assertThat(eventLog.contains("snapshot-diff:add:1210000000000000015:9493")).isTrue();
        assertThat(eventLog.contains("alert:commissioner:1210000000000000015")).isFalse();
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    /** A waiver claim that lost is not news: nothing happened. */
    @Test
    void aFailedWaiverClaimStaysOutOfTheAlertPath() {
        week14League();
        checkRunner.runCheck();

        nextCheckWithNothingElseChanged();
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.ROSTERS_PATH, "rosters-v1");
        SleeperStubs.stubJson(sleeper, TRANSACTIONS_PATH,
                "sleeper/transactions-week14-failed-waiver.json", "transactions-v2");
        checkRunner.runCheck();

        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.contains("alert:snapshot-diff:drop:1210000000000000014:4866"))
                .isFalse();
    }

    /**
     * A status outside Sleeper's published vocabulary is dropped rather
     * than read as news, and it is logged, so a value Sleeper starts
     * serving is findable instead of silent. It costs that one row and
     * not the read: the same feed carries a completed drop, and that
     * one still arrives.
     */
    @Test
    void aTransactionWithAnUnknownStatusIsDroppedAndLoggedWithoutLosingTheFeed() {
        week14League();
        checkRunner.runCheck();

        ListAppender<ILoggingEvent> log = captureAdapterLog();
        nextCheckWithNothingElseChanged();
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-league-after-commissioner-drop.json", "rosters-v2");
        SleeperStubs.stubJson(sleeper, TRANSACTIONS_PATH,
                "sleeper/transactions-week14-unknown-status.json", "transactions-v2");
        checkRunner.runCheck();

        assertThat(eventLog.contains("alert:trade:1210000000000000013")).isFalse();
        assertThat(eventLog.contains("alert:snapshot-diff:drop:1210000000000000012:4866"))
                .isTrue();
        assertThat(log.list)
                .anyMatch(entry -> entry.getLevel() == Level.WARN
                        && entry.getFormattedMessage().contains("reversed")
                        && entry.getFormattedMessage().contains("1210000000000000013"));
    }

    /** The adapter's own log, read back for the length of one test. */
    private ListAppender<ILoggingEvent> captureAdapterLog() {
        adapterAppender = new ListAppender<>();
        adapterAppender.start();
        adapterLog().addAppender(adapterAppender);
        return adapterAppender;
    }

    @AfterEach
    void detachAdapterLog() {
        if (adapterAppender != null) {
            adapterLog().detachAppender(adapterAppender);
            adapterAppender = null;
        }
    }

    private static Logger adapterLog() {
        return (Logger) LoggerFactory.getLogger(SleeperAdapter.class);
    }

    @Test
    void aMutedDropClassStaysQuietUntilTheUserUnmutesIt() {
        week14League();
        checkRunner.runCheck();
        muteStore.mute("class:drop", clock.instant());

        checkAfterTheDrops();

        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.contains("alert:snapshot-diff:drop:1210000000000000002:4866"))
                .isFalse();

        // A Mute silences the message and never cancels the
        // Recommendation: unmuted, the drop is still news.
        muteStore.unmute("class:drop");
        nextCheckWithNothingElseChanged();
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.ROSTERS_PATH, "rosters-v2");
        SleeperStubs.stubNotModified(sleeper, TRANSACTIONS_PATH, "transactions-v2");
        checkRunner.runCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.contains("alert:snapshot-diff:drop:1210000000000000002:4866"))
                .isTrue();
    }
}
