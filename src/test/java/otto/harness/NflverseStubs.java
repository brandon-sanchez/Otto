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

    private static final String DOWNLOAD = "/nflverse/nflverse-data/releases/download/";
    public static final String STATS_2026_PATH = DOWNLOAD + "stats_player/stats_player_week_2026.csv";
    public static final String STATS_2025_PATH = DOWNLOAD + "stats_player/stats_player_week_2025.csv";
    public static final String DEPTH_2026_PATH = DOWNLOAD + "depth_charts/depth_charts_2026.csv";

    public static final String PLAYER_IDS_PATH = "/dynastyprocess/data/master/files/db_playerids.csv";

    private NflverseStubs() {
    }

    /** Every nflverse feed healthy, at the timestamps the release index reports. */
    public static void healthy(WireMockServer nflverse) {
        stubJson(nflverse, STATS_RELEASE_PATH, "nflverse/release-stats-player.json");
        stubJson(nflverse, DEPTH_RELEASE_PATH, "nflverse/release-depth-charts.json");
        stubCsv(nflverse, STATS_2026_PATH, "nflverse/stats-player-week-2026.csv");
        stubCsv(nflverse, STATS_2025_PATH, "nflverse/stats-player-week-2025.csv");
        stubCsv(nflverse, DEPTH_2026_PATH, "nflverse/depth-charts-2026.csv");
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
        stubCsv(nflverse, STATS_2026_PATH, "nflverse/stats-player-week-waivers.csv");
        stubCsv(nflverse, DEPTH_2026_PATH, "nflverse/depth-charts-waivers.csv");
        stubCsv(nflverse, PLAYER_IDS_PATH, "nflverse/db-playerids-waivers.csv");
    }

    /**
     * The same waiver week, but with two played weeks on record and a
     * full field of running backs: enough for a top-24 usage claim to
     * mean something, which is what a usage breakout rests on.
     */
    public static void waiverWeekWithTwoPlayedWeeks(WireMockServer nflverse) {
        waiverWeek(nflverse);
        stubCsv(nflverse, STATS_2026_PATH, "nflverse/stats-player-week-usage-breakout.csv");
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
