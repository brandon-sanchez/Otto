package otto.harness;

import com.github.tomakehurst.wiremock.WireMockServer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

/** Recorded-fixture stubs that make the fake Sleeper play a healthy league. */
public final class SleeperStubs {

    public static final String LEAGUE_PATH = "/v1/league/1389748403805650944";
    public static final String ROSTERS_PATH = LEAGUE_PATH + "/rosters";
    public static final String USERS_PATH = LEAGUE_PATH + "/users";
    public static final String PLAYERS_PATH = "/v1/players/nfl";
    public static final String STATE_PATH = "/v1/state/nfl";
    public static final String PROJECTIONS_PATH = "/v1/projections/nfl/regular/2026/2";
    public static final String SCORES_PATH = "/scores/nfl/regular/2026/2";
    public static final String TRANSACTIONS_PATH = LEAGUE_PATH + "/transactions/2";

    /**
     * The most-added players. The Watchlist watcher and the waiver
     * board read the same list at the same limit, so one poll and one
     * cache entry serve both.
     */
    public static final String TRENDING_PATH =
            "/v1/players/nfl/trending/add?lookback_hours=24&limit=25";

    /** A Check reads the week before as well, so a late trade stays in view. */
    public static final String TRANSACTIONS_WEEK_1_PATH = LEAGUE_PATH + "/transactions/1";

    /** Any player's news feed, whoever it is about. */
    private static final String ANY_NEWS_PATH = "/players/nfl/[^/]+/news";

    /** Loses to any stub registered for one player, which run at the default. */
    private static final int CATCH_ALL_PRIORITY = 10;

    private SleeperStubs() {
    }

    public static void healthyInSeason(WireMockServer sleeper) {
        stubJson(sleeper, PLAYERS_PATH, "sleeper/players-nfl.json", "players-v1");
        stubJson(sleeper, LEAGUE_PATH, "sleeper/league-in-season.json", "league-v1");
        stubJson(sleeper, ROSTERS_PATH, "sleeper/rosters.json", "rosters-v1");
        stubJson(sleeper, USERS_PATH, "sleeper/users.json", "users-v1");
        stubJson(sleeper, STATE_PATH, "sleeper/state-nfl.json", "state-v1");
        stubJson(sleeper, PROJECTIONS_PATH, "sleeper/projections-2026-2.json", "projections-v1");
        stubJson(sleeper, SCORES_PATH, "sleeper/scores-2026-2.json", "scores-v1");
        stubJson(sleeper, TRANSACTIONS_PATH, "sleeper/transactions-none.json", "transactions-v1");
        stubJson(sleeper, TRANSACTIONS_WEEK_1_PATH, "sleeper/transactions-none.json",
                "transactions-w1");
    }

    /**
     * A league before its draft. Teams nobody has claimed yet carry no
     * owner, and no roster holds players. Sleeper really answers this
     * way, so the fixture keeps the nulls rather than tidying them.
     */
    public static void preDraftWithUnclaimedTeams(WireMockServer sleeper) {
        stubJson(sleeper, PLAYERS_PATH, "sleeper/players-nfl.json", "players-v1");
        stubJson(sleeper, LEAGUE_PATH, "sleeper/league-pre-draft.json", "league-v1");
        stubJson(sleeper, ROSTERS_PATH, "sleeper/rosters-pre-draft.json", "rosters-v1");
        stubJson(sleeper, USERS_PATH, "sleeper/users.json", "users-v1");
        stubJson(sleeper, STATE_PATH, "sleeper/state-nfl.json", "state-v1");
    }

    /**
     * Per-player news. The limit rides in the query string and the news
     * feed is never cached, so the stub matches on the path alone.
     */
    public static void stubNews(WireMockServer server, String playerId, String fixture) {
        server.stubFor(get(urlPathEqualTo("/players/nfl/" + playerId + "/news"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(Fixtures.read(fixture))));
    }

    /**
     * A league whose free agents make a waiver board: a purpose-built
     * players file, the user's roster with a bye-hit starting slot, the
     * projections that set every replacement level, and Sleeper's
     * trending adds.
     */
    public static void waiverWeek(WireMockServer sleeper) {
        stubJson(sleeper, PLAYERS_PATH, "sleeper/players-nfl-waivers.json", "players-v1");
        stubJson(sleeper, LEAGUE_PATH, "sleeper/league-in-season.json", "league-v1");
        stubJson(sleeper, ROSTERS_PATH, "sleeper/rosters-waivers.json", "rosters-v1");
        stubJson(sleeper, USERS_PATH, "sleeper/users.json", "users-v1");
        stubJson(sleeper, STATE_PATH, "sleeper/state-nfl.json", "state-v1");
        stubJson(sleeper, PROJECTIONS_PATH, "sleeper/projections-waivers.json", "projections-v1");
        stubJson(sleeper, SCORES_PATH, "sleeper/scores-2026-2.json", "scores-v1");
        stubJson(sleeper, TRENDING_PATH, "sleeper/trending-add.json", "trending-v1");
        noNewsByDefault(sleeper);
        stubNews(sleeper, "10001", "sleeper/news-10001.json");
        stubNews(sleeper, "10003", "sleeper/news-10003.json");
        stubNews(sleeper, "10004", "sleeper/news-10004.json");
        stubNews(sleeper, "10005", "sleeper/news-10005.json");
    }

    /**
     * An empty feed for every player nobody stubbed news for. A waiver
     * board reads news for a shortlist, and a missing stub would read
     * as a broken feed rather than as a quiet one.
     */
    public static void noNewsByDefault(WireMockServer server) {
        server.stubFor(get(urlPathMatching(ANY_NEWS_PATH))
                .atPriority(CATCH_ALL_PRIORITY)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));
    }

    public static void stubJson(WireMockServer server, String path, String fixture, String etag) {
        server.stubFor(get(urlEqualTo(path)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withHeader("ETag", "\"" + etag + "\"")
                .withBody(Fixtures.read(fixture))));
    }

    public static void stubNotModified(WireMockServer server, String path, String etag) {
        server.stubFor(get(urlEqualTo(path))
                .withHeader("If-None-Match", equalTo("\"" + etag + "\""))
                .willReturn(aResponse().withStatus(304)));
    }

    public static void allNotModified(WireMockServer server) {
        stubNotModified(server, PLAYERS_PATH, "players-v1");
        stubNotModified(server, LEAGUE_PATH, "league-v1");
        stubNotModified(server, ROSTERS_PATH, "rosters-v1");
        stubNotModified(server, USERS_PATH, "users-v1");
        stubNotModified(server, STATE_PATH, "state-v1");
        stubNotModified(server, PROJECTIONS_PATH, "projections-v1");
        stubNotModified(server, SCORES_PATH, "scores-v1");
        stubNotModified(server, TRANSACTIONS_PATH, "transactions-v1");
        stubNotModified(server, TRANSACTIONS_WEEK_1_PATH, "transactions-w1");
        stubNotModified(server, TRENDING_PATH, "trending-v1");
    }

    /**
     * Sleeper's most-added list. Only a non-empty Watchlist makes Otto
     * ask for it, so this stub is opt-in rather than part of a healthy
     * league.
     */
    public static void stubTrending(WireMockServer server, String fixture, String etag) {
        stubJson(server, TRENDING_PATH, fixture, etag);
    }
}
