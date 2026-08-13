package otto;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckResult;
import otto.check.CheckRunner;
import otto.events.DiffKind;
import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.storage.FileDocumentBackend;
import otto.storage.JsonStore;
import otto.telegram.TelegramWebhook;
import otto.telegram.WebhookResult;
import otto.watchlist.WatchlistStore;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Watchlist: the players the user asks Otto to watch even when
 * nobody rosters them. Sleeper exposes no such list, so the user builds
 * it by chat, and the Snapshot Diff drives every Alert it produces - a
 * drop, a sharp national trend, a projection update, and the Snipe.
 *
 * The fixture league holds two teams: the user's (roster 1) and
 * GridironGoblin's (roster 2, Saquon Barkley). Puka Nacua is in the
 * Player Directory and on no roster at all.
 */
class WatchlistScenarioTest extends WireSeamTest {

    private static final String NACUA = "9493";
    private static final String BARKLEY = "4866";

    /**
     * A Watchlist candidate keys off the transaction event behind a
     * prefix of its own, so it stays tellable apart from the league
     * activity candidate the same drop produces.
     */
    private static final String WATCHLIST_ADD = "watchlist:snapshot-diff:add:";
    private static final String WATCHLIST_DROP = "watchlist:snapshot-diff:drop:";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private TelegramWebhook webhook;

    @Autowired
    private EventLog eventLog;

    @Autowired
    private WatchlistStore watchlist;

    @Autowired
    private OttoProperties properties;

    private void baselineCheck() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubTrending(sleeper, "sleeper/trending-add.json", "trending-v1");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.telegramCallbackAnswered(telegram);
        OutboundStubs.llmPhrases(llm, "Watchlist notice.");
        checkRunner.runCheck();
    }

    /** The next Check: everything unchanged unless a test restubs it. */
    private void nextCheck() {
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
    }

    private void ask(String text) {
        assertThat(webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage(text)))
                .isEqualTo(WebhookResult.OK);
    }

    private List<Event> alertsAbout(String keyPrefix) {
        return eventLog.all().stream()
                .filter(event -> event.type() == EventType.ALERT_SENT)
                .filter(event -> event.key().startsWith("alert:" + keyPrefix))
                .toList();
    }

    /**
     * A transaction event names the transaction before the player, so
     * the two ends are matched rather than one prefix.
     */
    private List<Event> alertsAbout(String keyPrefix, String playerId) {
        return alertsAbout(keyPrefix).stream()
                .filter(event -> event.key().endsWith(":" + playerId))
                .toList();
    }

    /**
     * The week sources a Check only reads in season. A draft never
     * fetches them, so the first in-season Check after one has nothing
     * cached and must be given real bodies rather than 304s.
     */
    private void weekSources() {
        SleeperStubs.stubJson(sleeper, SleeperStubs.STATE_PATH,
                "sleeper/state-nfl.json", "state-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH,
                "sleeper/projections-2026-2.json", "projections-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.SCORES_PATH,
                "sleeper/scores-2026-2.json", "scores-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.TRANSACTIONS_WEEK_1_PATH,
                "sleeper/transactions-none.json", "transactions-w1");
        SleeperStubs.stubTrending(sleeper, "sleeper/trending-add.json", "trending-v1");
    }

    private void watch(String playerId, String player, String position, String team) {
        watchlist.add(new WatchlistStore.Entry(playerId, player, position, team,
                clock.instant()));
    }

    /** The transactions Sleeper publishes for this week, and the rosters after them. */
    private void transacted(String transactions, String rosters) {
        SleeperStubs.stubJson(sleeper, SleeperStubs.TRANSACTIONS_PATH,
                transactions, "transactions-v2");
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH, rosters, "rosters-v2");
    }

    @Test
    void aSnipeAlwaysAlerts() {
        watch(NACUA, "Puka Nacua", "WR", "LAR");
        baselineCheck();

        nextCheck();
        transacted("sleeper/transactions-nacua-claimed.json",
                "sleeper/rosters-nacua-added.json");
        checkRunner.runCheck();

        // Another manager claimed him: the moment the Watchlist plan dies.
        assertThat(alertsAbout(WATCHLIST_ADD, NACUA)).hasSize(1);
        assertThat(alertsAbout(WATCHLIST_ADD, NACUA).getFirst().facts())
                .containsEntry("player", "Puka Nacua")
                .containsEntry("confidence", "HIGH")
                .containsEntry("toManager", "GridironGoblin");
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void aWatchlistPlayerDroppedAlerts() {
        watch(BARKLEY, "Saquon Barkley", "RB", "PHI");
        baselineCheck();

        nextCheck();
        transacted("sleeper/transactions-barkley-dropped.json",
                "sleeper/rosters-barkley-dropped.json");
        checkRunner.runCheck();

        assertThat(alertsAbout(WATCHLIST_DROP, BARKLEY)).hasSize(1);
        assertThat(alertsAbout(WATCHLIST_DROP, BARKLEY).getFirst().facts())
                .containsEntry("player", "Saquon Barkley")
                .containsEntry("fromManager", "GridironGoblin");
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void aPlayerNobodyWatchesNeverAlerts() {
        baselineCheck();

        nextCheck();
        transacted("sleeper/transactions-barkley-dropped.json",
                "sleeper/rosters-barkley-dropped.json");
        checkRunner.runCheck();

        // Nobody watches him, so the Watchlist says nothing. League
        // activity may still call the drop notable, and that is its
        // job - the two Alerts answer different questions.
        assertThat(alertsAbout(WATCHLIST_DROP, BARKLEY)).isEmpty();
    }

    /**
     * A draft is every manager filling a team, not news. Transactions
     * are only read in season, so a draft produces no claim events at
     * all - but the Check still diffs rosters and health whatever the
     * league is doing, so a status change mid-draft does reach the
     * Event Log. The league status on the event is what stops the first
     * in-season Check reading that window back as news.
     */
    @Test
    void nothingSeenBeforeTheSeasonAlertsOnceItStarts() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH,
                "sleeper/league-drafting.json", "league-drafting");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Should not be sent.");
        checkRunner.runCheck();

        // Mid-draft, a drafted starter is ruled out.
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.LEAGUE_PATH, "league-drafting");
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-mccaffrey-out.json", "players-v2");
        checkRunner.runCheck();

        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        // Kept in the record, stamped with what the league was doing:
        // skipped by detection, not thrown away.
        assertThat(eventLog.all())
                .filteredOn(event -> event.key().startsWith("snapshot-diff:status:4034"))
                .singleElement()
                .satisfies(event -> assertThat(event.facts())
                        .containsEntry("leagueStatus", "DRAFTING"));

        // The draft ends minutes later and Sleeper flips the league in
        // season, well inside the four-hour retry window.
        clock.advance(Duration.ofMinutes(20));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.PLAYERS_PATH, "players-v2");
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH,
                "sleeper/league-in-season.json", "league-v1");
        weekSources();
        checkRunner.runCheck();

        // The pre-season transition never becomes its own Alert. The
        // lineup it leaves illegal is a different matter: that is read
        // from current state on this Check, not from the old event, and
        // it is exactly what the user needs to hear.
        assertThat(alertsAbout("snapshot-diff:status:4034")).isEmpty();
    }

    /** A watched player claimed during a draft is not a Snipe either. */
    @Test
    void aWatchedPlayerDraftedByAnotherManagerNeverSnipes() {
        watch(NACUA, "Puka Nacua", "WR", "LAR");
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH,
                "sleeper/league-drafting.json", "league-drafting");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Should not be sent.");
        checkRunner.runCheck();

        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.LEAGUE_PATH, "league-drafting");
        transacted("sleeper/transactions-nacua-claimed.json",
                "sleeper/rosters-nacua-added.json");
        checkRunner.runCheck();

        assertThat(alertsAbout(WATCHLIST_ADD, NACUA)).isEmpty();
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));

        // In season the same claim is the Snipe it always was.
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.ROSTERS_PATH, "rosters-v2");
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH,
                "sleeper/league-in-season.json", "league-v1");
        weekSources();
        SleeperStubs.stubJson(sleeper, SleeperStubs.TRANSACTIONS_PATH,
                "sleeper/transactions-nacua-claimed.json", "transactions-v2");
        checkRunner.runCheck();

        assertThat(alertsAbout(WATCHLIST_ADD, NACUA)).singleElement()
                .satisfies(event -> assertThat(event.facts())
                        .containsEntry("confidence", "HIGH")
                        .containsEntry("leagueStatus", "IN_SEASON"));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    /**
     * An Event Log written before the stamp existed holds no stamps at
     * all - and the operator has one. An unstamped diff event must
     * therefore be refused rather than trusted, or the upgrade itself
     * would Alert on everything the old build had already recorded.
     */
    @Test
    void anUnstampedDiffEventIsRefusedRatherThanTrusted() {
        watch(NACUA, "Puka Nacua", "WR", "LAR");
        baselineCheck();

        // Exactly what a log written by the previous build looks like:
        // the facts, the kind, and no league status.
        eventLog.append(new Event(
                "snapshot-diff:drop:1789000000000000009:" + NACUA,
                EventType.SNAPSHOT_DIFF,
                clock.instant(),
                Map.of(DiffKind.factName(), DiffKind.DROP.fact(),
                        "playerId", NACUA, "player", "Puka Nacua", "team", "LAR",
                        "position", "WR", "fromManager", "GridironGoblin",
                        "userRoster", "false")));

        nextCheck();
        telegram.resetRequests();
        checkRunner.runCheck();

        assertThat(alertsAbout(WATCHLIST_DROP, NACUA)).isEmpty();
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));

        // The exemption is by event type, not by an absent field: a
        // Watchlist move only ever happens in season, so it still runs.
        eventLog.append(new Event(
                "watchlist:trending:%s:%d".formatted(NACUA, clock.instant().getEpochSecond()),
                EventType.WATCHLIST_MOVE,
                clock.instant(),
                Map.of("playerId", NACUA, "player", "Puka Nacua", "team", "LAR",
                        "adds", "41000", "lookbackHours", "24", "trendingLimit", "25")));

        nextCheck();
        telegram.resetRequests();
        checkRunner.runCheck();

        assertThat(alertsAbout("watchlist:trending:" + NACUA)).hasSize(1);
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    /**
     * The same holds before a draft starts. Two things keep this one
     * quiet: the stamp, and the daily pre-draft cadence, which puts any
     * pre-draft event well outside the four-hour retry window before a
     * Check can run again. So this test states the rule; the drafting
     * one above is what proves the stamp.
     */
    @Test
    void aRosterMoveBeforeTheDraftNeverSnipes() {
        watch(NACUA, "Puka Nacua", "WR", "LAR");
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH,
                "sleeper/league-pre-draft.json", "league-pre-draft");
        SleeperStubs.stubTrending(sleeper, "sleeper/trending-add.json", "trending-v1");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Should not be sent.");
        checkRunner.runCheck();

        // Pre-draft Checks run once a day, so the clock has to move on.
        clock.advance(Duration.ofHours(25));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.LEAGUE_PATH, "league-pre-draft");
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-mccaffrey-out.json", "players-v2");
        checkRunner.runCheck();

        assertThat(eventLog.all())
                .filteredOn(event -> event.key().startsWith("snapshot-diff:status:4034"))
                .singleElement()
                .satisfies(event -> assertThat(event.facts())
                        .containsEntry("leagueStatus", "PRE_DRAFT"));

        // The draft happens and the league goes in season. A pre-draft
        // Check runs daily, so the gate needs the clock moved on again.
        clock.advance(Duration.ofHours(25));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.PLAYERS_PATH, "players-v2");
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH,
                "sleeper/league-in-season.json", "league-v1");
        weekSources();
        telegram.resetRequests();
        CheckResult inSeason = checkRunner.runCheck();

        assertThat(inSeason.skipped()).isFalse();
        assertThat(alertsAbout("snapshot-diff:status:4034")).isEmpty();
    }

    /**
     * The user's own move is not news to him. He cut the player, so
     * telling him the man is a free agent would be telling him what he
     * just did - the same line league activity already draws for its
     * own drops.
     */
    @Test
    void aWatchedPlayerTheUserDropsHimselfIsNotNews() {
        watch("6813", "Dallas Goedert", "TE", "PHI");
        baselineCheck();

        nextCheck();
        transacted("sleeper/transactions-goedert-dropped-by-me.json",
                "sleeper/rosters-goedert-dropped.json");
        telegram.resetRequests();
        checkRunner.runCheck();

        assertThat(alertsAbout(WATCHLIST_DROP, "6813")).isEmpty();
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    /** A trigger the user switched off stops its Alerts, Snipes included. */
    @Test
    void theWatchlistTriggerSwitchedOffStopsEvenASnipe() {
        watch(NACUA, "Puka Nacua", "WR", "LAR");
        baselineCheck();

        llm.resetAll();
        OutboundStubs.llmCallsToolThenPhrases(llm, "manage_settings",
                "{\"action\":\"set\",\"name\":\"watchlist\",\"value\":\"off\"}", "Watchlist off.");
        ask("stop telling me about my watchlist");

        nextCheck();
        transacted("sleeper/transactions-nacua-claimed.json",
                "sleeper/rosters-nacua-added.json");
        telegram.resetRequests();
        checkRunner.runCheck();

        assertThat(alertsAbout(WATCHLIST_ADD, NACUA)).isEmpty();
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void aWatchlistPlayerTrendingUpSharplyAlerts() {
        watch(NACUA, "Puka Nacua", "WR", "LAR");
        baselineCheck();

        // He was not in Sleeper's top adds; now he leads them.
        nextCheck();
        SleeperStubs.stubTrending(sleeper, "sleeper/trending-add-nacua.json", "trending-v2");
        checkRunner.runCheck();

        assertThat(alertsAbout("watchlist:trending:" + NACUA)).hasSize(1);
        assertThat(alertsAbout("watchlist:trending:" + NACUA).getFirst().facts())
                .containsEntry("adds", "41000");

        // Still trending on the next Check: he did not just enter, so
        // the Alert does not repeat.
        nextCheck();
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.TRENDING_PATH, "trending-v2");
        checkRunner.runCheck();
        assertThat(alertsAbout("watchlist:trending:" + NACUA)).hasSize(1);
    }

    /**
     * A player who is already among the most-added when Otto first
     * looks has not just entered anything. Without an earlier list to
     * compare against, the first reading only learns the state.
     */
    @Test
    void aPlayerAlreadyTrendingOnTheFirstReadingStaysQuiet() {
        watch(NACUA, "Puka Nacua", "WR", "LAR");
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubTrending(sleeper, "sleeper/trending-add-nacua.json", "trending-v1");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Should not be sent.");
        checkRunner.runCheck();

        assertThat(alertsAbout("watchlist:trending:" + NACUA)).isEmpty();
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));

        // He stays on the list, so later Checks stay quiet too.
        nextCheck();
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.TRENDING_PATH, "trending-v1");
        checkRunner.runCheck();
        assertThat(alertsAbout("watchlist:trending:" + NACUA)).isEmpty();
    }

    @Test
    void aWatchlistPlayerProjectionUpdateAlerts() {
        watch(BARKLEY, "Saquon Barkley", "RB", "PHI");
        baselineCheck();

        nextCheck();
        SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH,
                "sleeper/projections-barkley-down.json", "projections-v2");
        checkRunner.runCheck();

        // 19.2 in league scoring, then 10.8: Java owns both numbers.
        assertThat(alertsAbout("watchlist:projection:" + BARKLEY)).hasSize(1);
        assertThat(alertsAbout("watchlist:projection:" + BARKLEY).getFirst().facts())
                .containsEntry("was", "19.2")
                .containsEntry("now", "10.8")
                .containsEntry("move", "-8.4");
    }

    /**
     * A new week reprices everybody, and none of that is news about a
     * player. The rollover sets a new baseline instead of Alerting on
     * every watched player at once.
     */
    @Test
    void aNewWeekIsABaselineAndNotAProjectionUpdate() {
        watch(BARKLEY, "Saquon Barkley", "RB", "PHI");
        baselineCheck();

        // Week 3 opens with quite different numbers for the same player.
        nextCheck();
        sleeper.resetAll();
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubTrending(sleeper, "sleeper/trending-add.json", "trending-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.STATE_PATH,
                "sleeper/state-nfl-week3.json", "state-v2");
        SleeperStubs.stubJson(sleeper, "/v1/projections/nfl/regular/2026/3",
                "sleeper/projections-barkley-down.json", "projections-w3");
        SleeperStubs.stubJson(sleeper, "/scores/nfl/regular/2026/3",
                "sleeper/scores-2026-2.json", "scores-w3");
        telegram.resetRequests();
        checkRunner.runCheck();

        assertThat(alertsAbout("watchlist:projection:" + BARKLEY)).isEmpty();
    }

    /**
     * A feed that has never answered is not a reading. Trending being
     * down on the first Check must not make everybody look new once it
     * comes back.
     */
    @Test
    void aTrendingFeedThatWasDownFirstDoesNotReportTheWholeListAsNew() {
        watch(NACUA, "Puka Nacua", "WR", "LAR");
        SleeperStubs.healthyInSeason(sleeper);
        sleeper.stubFor(get(urlEqualTo(SleeperStubs.TRENDING_PATH))
                .willReturn(aResponse().withStatus(500)));
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Notice.");
        checkRunner.runCheck();

        // The feed comes up and he is already on the list: that is the
        // first reading, so it is a baseline and not an entry.
        nextCheck();
        SleeperStubs.stubTrending(sleeper, "sleeper/trending-add-nacua.json", "trending-v1");
        telegram.resetRequests();
        checkRunner.runCheck();

        assertThat(alertsAbout("watchlist:trending:" + NACUA)).isEmpty();
    }

    /**
     * Watchlist news is not a lineup problem. A Done tap on one must
     * never grow into the 20-minute "I see no lineup change" nudge.
     */
    @Test
    void aDoneTapOnWatchlistNewsNeverBecomesANudge() {
        watch(NACUA, "Puka Nacua", "WR", "LAR");
        baselineCheck();

        nextCheck();
        transacted("sleeper/transactions-nacua-claimed.json",
                "sleeper/rosters-nacua-added.json");
        checkRunner.runCheck();
        assertThat(alertsAbout(WATCHLIST_ADD, NACUA)).hasSize(1);

        webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap("done:1"));

        clock.advance(Duration.ofMinutes(21));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.ROSTERS_PATH, "rosters-v2");
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.TRANSACTIONS_PATH, "transactions-v2");
        telegram.resetRequests();
        checkRunner.runCheck();

        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void aProjectionThatBarelyMovesStaysQuiet() {
        watch(BARKLEY, "Saquon Barkley", "RB", "PHI");
        baselineCheck();

        nextCheck();
        SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH,
                "sleeper/projections-barkley-nudged.json", "projections-v2");
        checkRunner.runCheck();

        assertThat(alertsAbout("watchlist:projection:" + BARKLEY)).isEmpty();
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    /**
     * A feed that breaks is said plainly and costs nothing else: the
     * stored reading stays as it was, so an entry into the most-added
     * list is still news on the Check after the feed recovers.
     */
    @Test
    void aBrokenTrendingFeedSelfReportsAndKeepsTheLastReading() {
        watch(NACUA, "Puka Nacua", "WR", "LAR");
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubTrending(sleeper, "sleeper/trending-add.json", "trending-v1");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Watchlist notice.");
        checkRunner.runCheck();

        // The feed goes down: one self-report, no watchlist Alert.
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        sleeper.stubFor(get(urlEqualTo(SleeperStubs.TRENDING_PATH))
                .willReturn(aResponse().withStatus(500)));
        telegram.resetRequests();
        checkRunner.runCheck();

        assertThat(eventLog.all()).anyMatch(event ->
                event.type() == EventType.SOURCE_UNAVAILABLE
                        && event.facts().getOrDefault("source", "").contains("trending"));
        assertThat(alertsAbout("watchlist:trending:" + NACUA)).isEmpty();

        // Back up, and now he is in the list: still news.
        nextCheck();
        SleeperStubs.stubTrending(sleeper, "sleeper/trending-add-nacua.json", "trending-v2");
        checkRunner.runCheck();
        assertThat(alertsAbout("watchlist:trending:" + NACUA)).hasSize(1);
    }

    @Test
    void anEmptyWatchlistNeverAsksSleeperForTrends() {
        baselineCheck();

        sleeper.verify(0, getRequestedFor(urlEqualTo(SleeperStubs.TRENDING_PATH)));
    }

    @Test
    void theWatchlistIsManagedByChat() {
        baselineCheck();
        llm.resetAll();
        telegram.resetRequests();

        OutboundStubs.llmCallsToolThenPhrases(llm, "manage_watchlist",
                "{\"action\":\"add\",\"player\":\"Puka Nacua\"}", "Watching Puka Nacua.");
        ask("watch Puka Nacua");

        assertThat(watchlist.contains(NACUA)).isTrue();
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Puka Nacua"))
                .withRequestBody(containing("added")));

        // The season-long record answers when he joined the list.
        assertThat(eventLog.all())
                .anyMatch(event -> event.type() == EventType.USER_ACTION
                        && "watchlist-add".equals(event.facts().get("action"))
                        && NACUA.equals(event.facts().get("playerId")));

        llm.resetAll();
        OutboundStubs.llmCallsToolThenPhrases(llm, "manage_watchlist",
                "{\"action\":\"list\"}", "Puka Nacua is on your watchlist.");
        ask("what is on my watchlist?");
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Puka Nacua"))
                .withRequestBody(containing("LAR")));

        llm.resetAll();
        OutboundStubs.llmCallsToolThenPhrases(llm, "manage_watchlist",
                "{\"action\":\"remove\",\"player\":\"Puka Nacua\"}", "Dropped him from the list.");
        ask("stop watching Puka Nacua");
        assertThat(watchlist.contains(NACUA)).isFalse();
        assertThat(eventLog.all())
                .anyMatch(event -> event.type() == EventType.USER_ACTION
                        && "watchlist-remove".equals(event.facts().get("action"))
                        && NACUA.equals(event.facts().get("playerId")));
    }

    @Test
    void aNameThatMatchesNobodySaysSoRatherThanGuessing() {
        baselineCheck();
        llm.resetAll();

        OutboundStubs.llmCallsToolThenPhrases(llm, "manage_watchlist",
                "{\"action\":\"add\",\"player\":\"Nobody McNoface\"}",
                "I do not know that player.");
        ask("watch Nobody McNoface");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("no player I know of matches")));
        assertThat(watchlist.all()).isEmpty();
    }

    /**
     * The Watchlist is a stored document, so a cold reader - a new
     * process after a restart - sees exactly what the chat left behind.
     */
    @Test
    void theWatchlistSurvivesChecksAndRestarts() {
        watch(NACUA, "Puka Nacua", "WR", "LAR");
        baselineCheck();
        nextCheck();
        checkRunner.runCheck();

        WatchlistStore coldRead = new WatchlistStore(new JsonStore(new FileDocumentBackend(properties)));
        assertThat(coldRead.all()).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.playerId()).isEqualTo(NACUA);
                    assertThat(entry.player()).isEqualTo("Puka Nacua");
                    assertThat(entry.at()).isEqualTo(Instant.parse("2026-09-15T17:00:00Z"));
                });
    }
}
