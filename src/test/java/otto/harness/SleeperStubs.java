package otto.harness;

import com.github.tomakehurst.wiremock.WireMockServer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/** Recorded-fixture stubs that make the fake Sleeper play a healthy league. */
public final class SleeperStubs {

    public static final String LEAGUE_PATH = "/v1/league/1389748403805650944";
    public static final String ROSTERS_PATH = LEAGUE_PATH + "/rosters";
    public static final String USERS_PATH = LEAGUE_PATH + "/users";
    public static final String PLAYERS_PATH = "/v1/players/nfl";
    public static final String STATE_PATH = "/v1/state/nfl";
    public static final String PROJECTIONS_PATH = "/v1/projections/nfl/regular/2026/2";
    public static final String SCORES_PATH = "/scores/nfl/regular/2026/2";

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
    }
}
