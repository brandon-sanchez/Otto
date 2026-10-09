package otto.harness;

import com.github.tomakehurst.wiremock.WireMockServer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * Recorded-fixture stubs for the three feeds behind player analysis: the
 * nflverse release index (the timestamp check), the release assets
 * themselves, and the DynastyProcess player-id mapping.
 *
 * The fixtures keep the real column sets and the real JSON keys, trimmed
 * to the rows the scenarios need.
 */
public final class NflverseStubs {

    public static final String STATS_RELEASE_PATH =
            "/repos/nflverse/nflverse-data/releases/tags/stats_player";
    public static final String DEPTH_RELEASE_PATH =
            "/repos/nflverse/nflverse-data/releases/tags/depth_charts";
    public static final String ROSTERS_RELEASE_PATH =
            "/repos/nflverse/nflverse-data/releases/tags/weekly_rosters";
    public static final String SNAPS_RELEASE_PATH =
            "/repos/nflverse/nflverse-data/releases/tags/snap_counts";
    public static final String SCHEDULE_RELEASE_PATH =
            "/repos/nflverse/nflverse-data/releases/tags/schedules";
    public static final String FTN_RELEASE_PATH =
            "/repos/nflverse/nflverse-data/releases/tags/ftn_charting";

    private static final String DOWNLOAD = "/nflverse/nflverse-data/releases/download/";
    public static final String STATS_2026_PATH = DOWNLOAD + "stats_player/stats_player_week_2026.csv";
    public static final String STATS_2025_PATH = DOWNLOAD + "stats_player/stats_player_week_2025.csv";
    public static final String DEPTH_2026_PATH = DOWNLOAD + "depth_charts/depth_charts_2026.csv";
    public static final String ROSTERS_2026_PATH =
            DOWNLOAD + "weekly_rosters/roster_weekly_2026.csv";
    public static final String SNAPS_2026_PATH = DOWNLOAD + "snap_counts/snap_counts_2026.csv";
    public static final String SNAPS_2025_PATH = DOWNLOAD + "snap_counts/snap_counts_2025.csv";
    public static final String SCHEDULE_PATH = DOWNLOAD + "schedules/games.csv";
    public static final String FTN_2026_PATH = DOWNLOAD + "ftn_charting/ftn_charting_2026.csv";

    public static final String PLAYER_IDS_PATH = "/dynastyprocess/data/master/files/db_playerids.csv";

    private NflverseStubs() {
    }

    /** Every nflverse feed healthy, at the timestamps the release index reports. */
    public static void healthy(WireMockServer nflverse) {
        schedule(nflverse);
        ftnCharting(nflverse);
        stubJson(nflverse, STATS_RELEASE_PATH, "nflverse/release-stats-player.json");
        stubJson(nflverse, DEPTH_RELEASE_PATH, "nflverse/release-depth-charts.json");
        stubJson(nflverse, ROSTERS_RELEASE_PATH, "nflverse/release-weekly-rosters.json");
        stubJson(nflverse, SNAPS_RELEASE_PATH, "nflverse/release-snap-counts.json");
        stubCsv(nflverse, STATS_2026_PATH, "nflverse/stats-player-week-2026.csv");
        stubCsv(nflverse, STATS_2025_PATH, "nflverse/stats-player-week-2025.csv");
        stubCsv(nflverse, DEPTH_2026_PATH, "nflverse/depth-charts-2026.csv");
        stubCsv(nflverse, ROSTERS_2026_PATH, "nflverse/roster-weekly-2026.csv");
        stubCsv(nflverse, SNAPS_2026_PATH, "nflverse/snap-counts-2026.csv");
        stubCsv(nflverse, SNAPS_2025_PATH, "nflverse/snap-counts-2026.csv");
        stubCsv(nflverse, PLAYER_IDS_PATH, "nflverse/db-playerids.csv");
    }

    /**
     * The nflverse side of a waiver week: a depth chart that shows one
     * promotion since the chart before it, stat lines that rank four
     * defences, and the id mapping the join needs.
     */
    public static void waiverWeek(WireMockServer nflverse) {
        schedule(nflverse);
        ftnCharting(nflverse);
        stubJson(nflverse, STATS_RELEASE_PATH, "nflverse/release-stats-player.json");
        stubJson(nflverse, DEPTH_RELEASE_PATH, "nflverse/release-depth-charts.json");
        stubJson(nflverse, ROSTERS_RELEASE_PATH, "nflverse/release-weekly-rosters.json");
        stubJson(nflverse, SNAPS_RELEASE_PATH, "nflverse/release-snap-counts.json");
        stubCsv(nflverse, STATS_2026_PATH, "nflverse/stats-player-week-waivers.csv");
        stubCsv(nflverse, DEPTH_2026_PATH, "nflverse/depth-charts-waivers.csv");
        stubCsv(nflverse, ROSTERS_2026_PATH, "nflverse/roster-weekly-waivers.csv");
        stubCsv(nflverse, SNAPS_2026_PATH, "nflverse/snap-counts-waivers.csv");
        stubCsv(nflverse, PLAYER_IDS_PATH, "nflverse/db-playerids-waivers.csv");
    }

    /**
     * The same waiver week with three played weeks on record, over
     * which one back's share of his own backfield climbs 54%, 58%, 62%.
     * That is the slow lane and the rising trend in one file.
     */
    public static void waiverWeekWithAGrowingRole(WireMockServer nflverse) {
        waiverWeek(nflverse);
        stubCsv(nflverse, STATS_2026_PATH, "nflverse/stats-player-week-usage-breakout.csv");
        stubCsv(nflverse, SNAPS_2026_PATH, "nflverse/snap-counts-growing-role.csv");
    }

    /**
     * A week in which two free agents took the offence themselves, with
     * no injury behind either: a receiver on 39% of his team's targets
     * and a back on 91% of his backfield's work, both in one game.
     */
    public static void waiverWeekWithEarnedRoles(WireMockServer nflverse) {
        waiverWeek(nflverse);
        stubCsv(nflverse, STATS_2026_PATH, "nflverse/stats-player-week-earned-roles.csv");
        stubCsv(nflverse, SNAPS_2026_PATH, "nflverse/snap-counts-earned-roles.csv");
    }

    /**
     * The same waiver week, except that the man ahead on the chart is
     * designated to return from injured reserve rather than gone for
     * the season. His role is a loan, so it is not a breakout.
     */
    public static void waiverWeekWithAReturningStarter(WireMockServer nflverse) {
        waiverWeek(nflverse);
        stubCsv(nflverse, ROSTERS_2026_PATH, "nflverse/roster-weekly-waivers-returning.csv");
    }

    /**
     * Three played weeks, over which one tight end grows into a role,
     * one back takes a loaned one outright, one receiver clears the bar
     * in two weeks with a gap between them, and one back has not played
     * since week 2. The man ahead is designated to return, so only the
     * player's own share can tag anybody here.
     */
    public static void waiverWeekWithGrowingAndStaleShares(WireMockServer nflverse) {
        waiverWeekWithAReturningStarter(nflverse);
        stubCsv(nflverse, STATS_2026_PATH, "nflverse/stats-player-week-share-lanes.csv");
        stubCsv(nflverse, SNAPS_2026_PATH, "nflverse/snap-counts-share-lanes.csv");
    }

    /**
     * The week before any game of the season is played. The newest
     * stats file the system can name is last season's final record, so
     * the earned-roles week sits in the 2025 asset instead of the 2026
     * one - the same big shares, all of them last December's.
     *
     * The 2026 asset stays stubbed and is never asked for, which is the
     * point: in this state the feed names last season's file, and the
     * scenario checks that no request for the current season's is made.
     */
    public static void waiverWeekBeforeAnyGameIsPlayed(WireMockServer nflverse) {
        waiverWeek(nflverse);
        beforeAnyGameIsPlayed(nflverse);
        stubCsv(nflverse, STATS_2025_PATH, "nflverse/stats-player-week-earned-roles.csv");
    }

    /**
     * The schedule as published before the season's first kickoff: every
     * 2026 game listed, none with a result. It is what tells the stats
     * and snap feeds that last season's file is still the newest record.
     */
    public static void beforeAnyGameIsPlayed(WireMockServer nflverse) {
        stubCsv(nflverse, SCHEDULE_PATH, "nflverse/games-preseason.csv");
    }

    /** The weekly-roster feed is gone, so no absence can be read either way. */
    public static void waiverWeekWithNoRosterStandings(WireMockServer nflverse) {
        waiverWeek(nflverse);
        nflverse.stubFor(get(urlEqualTo(ROSTERS_2026_PATH))
                .willReturn(aResponse().withStatus(404)));
    }

    /**
     * The 2025 and 2026 seasons as published on 2026-10-04: weeks 1 to 3
     * final, week 4 with only its Thursday game played, week 5 not yet
     * started. Trimmed to a few games a week, plus one 2024 game and one
     * 2025 playoff game that the feed must drop.
     */
    private static void schedule(WireMockServer nflverse) {
        stubJson(nflverse, SCHEDULE_RELEASE_PATH, "nflverse/release-schedules.json");
        stubCsv(nflverse, SCHEDULE_PATH, "nflverse/games.csv");
    }

    /**
     * The feeds as they stand after week 3, one row per team per week
     * against the schedule's games. The stats and snap counts hold every
     * final game, Thursday's week 4 game included, but the stats have no
     * Arizona line in week 3; the rosters run through week 5 but have
     * not yet listed New Orleans in week 4, who play on the Monday;
     * every team's newest depth chart is dated 30 September, inside
     * week 4's window, except Pittsburgh's, dated with a bare "2026-09-29"
     * on the Eastern day the window opens, and New Orleans', a week older.
     */
    public static void afterWeek3(WireMockServer nflverse) {
        healthy(nflverse);
        stubCsv(nflverse, STATS_2026_PATH, "nflverse/stats-player-week-2026-weeks.csv");
        stubCsv(nflverse, ROSTERS_2026_PATH, "nflverse/roster-weekly-2026-weeks.csv");
        stubCsv(nflverse, DEPTH_2026_PATH, "nflverse/depth-charts-2026-weeks.csv");
        stubCsv(nflverse, SNAPS_2026_PATH, "nflverse/snap-counts-2026-weeks.csv");
    }

    /**
     * The depth charts republished with a chart for New Orleans dated
     * inside week 4, a fullback on top of the back who was RB1 before,
     * and an older Green Bay chart from the week before on which its
     * back was RB2. Every team's newest chart but New Orleans' is the
     * one already held.
     */
    public static void depthChartsNewerForNewOrleans(WireMockServer nflverse) {
        stubJson(nflverse, DEPTH_RELEASE_PATH, "nflverse/release-depth-charts-refreshed.json");
        stubCsv(nflverse, DEPTH_2026_PATH, "nflverse/depth-charts-2026-weeks-newer.csv");
    }

    public static void depthChartsRerankedForAtlanta(WireMockServer nflverse) {
        stubJson(nflverse, DEPTH_RELEASE_PATH, "nflverse/release-depth-charts-refreshed.json");
        stubCsv(nflverse, DEPTH_2026_PATH, "nflverse/depth-charts-2026-weeks-reranked.csv");
    }

    /**
     * FTN's 2026 charting as published on 2026-10-03, trimmed to three
     * plays of each final game in the schedule fixture. Weeks 1 and 2
     * carry the bulk re-pull of 28 September, week 3 its own of 30
     * September, and week 4's Thursday game 3 October.
     */
    private static void ftnCharting(WireMockServer nflverse) {
        stubJson(nflverse, FTN_RELEASE_PATH, "nflverse/release-ftn-charting.json");
        stubCsv(nflverse, FTN_2026_PATH, "nflverse/ftn-charting-2026.csv");
    }

    /**
     * FTN re-pulls weeks 1 and 2 in bulk: every one of their plays carries
     * a new date_pulled and nothing else about them changes. The same
     * release adds a fourth charted play to Atlanta at Green Bay in week 3.
     */
    public static void ftnRePulled(WireMockServer nflverse) {
        stubJson(nflverse, FTN_RELEASE_PATH, "nflverse/release-ftn-charting-repulled.json");
        stubCsv(nflverse, FTN_2026_PATH, "nflverse/ftn-charting-2026-repulled.csv");
    }

    /** The schedule's release index is down, and no schedule has ever been read. */
    public static void scheduleUnavailable(WireMockServer nflverse) {
        nflverse.stubFor(get(urlEqualTo(SCHEDULE_RELEASE_PATH))
                .willReturn(aResponse().withStatus(503)));
    }

    /** The stats republished with San Francisco's week 2 rushing yards corrected. */
    public static void weeklyStatsCorrected(WireMockServer nflverse) {
        weeklyStatsRepublished(nflverse);
        stubCsv(nflverse, STATS_2026_PATH, "nflverse/stats-player-week-2026-weeks-corrected.csv");
    }

    /**
     * The stats republished with the same numbers, the rows and columns in
     * reverse order and nflverse's own fantasy points changed - a column
     * Otto never reads.
     */
    public static void weeklyStatsReordered(WireMockServer nflverse) {
        weeklyStatsRepublished(nflverse);
        stubCsv(nflverse, STATS_2026_PATH, "nflverse/stats-player-week-2026-weeks-reordered.csv");
    }

    /** The stats asset is republished again a day later, its bytes unchanged. */
    public static void weeklyStatsRepublishedAgain(WireMockServer nflverse) {
        stubJson(nflverse, STATS_RELEASE_PATH, "nflverse/release-stats-player-republished.json");
    }

    public static void weeklyStatsWithoutSanFranciscoWeek2(WireMockServer nflverse) {
        weeklyStatsRepublished(nflverse);
        stubCsv(nflverse, STATS_2026_PATH, "nflverse/stats-player-week-2026-weeks-dropped-sf.csv");
    }

    public static void weeklyStatsSanFranciscoWeek2ReturnsCorrected(WireMockServer nflverse) {
        weeklyStatsRepublishedAgain(nflverse);
        stubCsv(nflverse, STATS_2026_PATH, "nflverse/stats-player-week-2026-weeks-corrected.csv");
    }

    /** The weekly stats asset is republished: its timestamp moves forward. */
    public static void weeklyStatsRepublished(WireMockServer nflverse) {
        stubJson(nflverse, STATS_RELEASE_PATH, "nflverse/release-stats-player-refreshed.json");
    }

    public static void stubJson(WireMockServer server, String path, String fixture) {
        server.stubFor(get(urlEqualTo(path)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(Fixtures.read(fixture))));
    }

    public static void stubCsv(WireMockServer server, String path, String fixture) {
        server.stubFor(get(urlEqualTo(path)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/csv")
                .withHeader("ETag", "\"" + fixture + "\"")
                .withBody(Fixtures.read(fixture))));
    }
}
