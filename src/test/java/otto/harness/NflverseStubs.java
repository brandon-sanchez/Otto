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

    private static final String DOWNLOAD = "/nflverse/nflverse-data/releases/download/";
    public static final String STATS_2026_PATH = DOWNLOAD + "stats_player/stats_player_week_2026.csv";
    public static final String STATS_2025_PATH = DOWNLOAD + "stats_player/stats_player_week_2025.csv";
    public static final String DEPTH_2026_PATH = DOWNLOAD + "depth_charts/depth_charts_2026.csv";
    public static final String ROSTERS_2026_PATH =
            DOWNLOAD + "weekly_rosters/roster_weekly_2026.csv";

    public static final String PLAYER_IDS_PATH = "/dynastyprocess/data/master/files/db_playerids.csv";

    private NflverseStubs() {
    }

    /** Every nflverse feed healthy, at the timestamps the release index reports. */
    public static void healthy(WireMockServer nflverse) {
        stubJson(nflverse, STATS_RELEASE_PATH, "nflverse/release-stats-player.json");
        stubJson(nflverse, DEPTH_RELEASE_PATH, "nflverse/release-depth-charts.json");
        stubJson(nflverse, ROSTERS_RELEASE_PATH, "nflverse/release-weekly-rosters.json");
        stubCsv(nflverse, STATS_2026_PATH, "nflverse/stats-player-week-2026.csv");
        stubCsv(nflverse, STATS_2025_PATH, "nflverse/stats-player-week-2025.csv");
        stubCsv(nflverse, DEPTH_2026_PATH, "nflverse/depth-charts-2026.csv");
        stubCsv(nflverse, ROSTERS_2026_PATH, "nflverse/roster-weekly-2026.csv");
        stubCsv(nflverse, PLAYER_IDS_PATH, "nflverse/db-playerids.csv");
    }

    /**
     * The nflverse side of a waiver week: a depth chart that shows one
     * promotion since the chart before it, stat lines that rank four
     * defences, and the id mapping the join needs.
     */
    public static void waiverWeek(WireMockServer nflverse) {
        stubJson(nflverse, STATS_RELEASE_PATH, "nflverse/release-stats-player.json");
        stubJson(nflverse, DEPTH_RELEASE_PATH, "nflverse/release-depth-charts.json");
        stubJson(nflverse, ROSTERS_RELEASE_PATH, "nflverse/release-weekly-rosters.json");
        stubCsv(nflverse, STATS_2026_PATH, "nflverse/stats-player-week-waivers.csv");
        stubCsv(nflverse, DEPTH_2026_PATH, "nflverse/depth-charts-waivers.csv");
        stubCsv(nflverse, ROSTERS_2026_PATH, "nflverse/roster-weekly-waivers.csv");
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
    }

    /**
     * A week in which two free agents took the offence themselves, with
     * no injury behind either: a receiver on 39% of his team's targets
     * and a back on 91% of his backfield's work, both in one game.
     */
    public static void waiverWeekWithEarnedRoles(WireMockServer nflverse) {
        waiverWeek(nflverse);
        stubCsv(nflverse, STATS_2026_PATH, "nflverse/stats-player-week-earned-roles.csv");
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
    }

    /** The weekly-roster feed is gone, so no absence can be read either way. */
    public static void waiverWeekWithNoRosterStandings(WireMockServer nflverse) {
        waiverWeek(nflverse);
        nflverse.stubFor(get(urlEqualTo(ROSTERS_2026_PATH))
                .willReturn(aResponse().withStatus(404)));
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
