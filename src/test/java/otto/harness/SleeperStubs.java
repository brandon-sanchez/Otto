package otto.harness;

import com.github.tomakehurst.wiremock.WireMockServer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/** Recorded-fixture stubs that make the fake Sleeper play a healthy league. */
public final class SleeperStubs {

    public static final String LEAGUE_PATH = "/v1/league/1389748403805650944";
    public static final String ROSTERS_PATH = LEAGUE_PATH + "/rosters";
    public static final String USERS_PATH = LEAGUE_PATH + "/users";
    public static final String PLAYERS_PATH = "/v1/players/nfl";

    private SleeperStubs() {
    }

    public static void healthyInSeason(WireMockServer sleeper) {
        stubJson(sleeper, PLAYERS_PATH, "sleeper/players-nfl.json", "players-v1");
        stubJson(sleeper, LEAGUE_PATH, "sleeper/league-in-season.json", "league-v1");
        stubJson(sleeper, ROSTERS_PATH, "sleeper/rosters.json", "rosters-v1");
        stubJson(sleeper, USERS_PATH, "sleeper/users.json", "users-v1");
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
    }
}
