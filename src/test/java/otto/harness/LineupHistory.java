package otto.harness;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.github.tomakehurst.wiremock.WireMockServer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/**
 * Played weeks of the user's lineup, served as Sleeper's matchups, so
 * the weekly habit capture has results to measure.
 */
public final class LineupHistory {

    private LineupHistory() {
    }

    /**
     * Serves one played week: the user's roster 1 with its starters in
     * slot order, "0" for an empty slot, and what every player on it
     * scored, beside one rival.
     *
     * @param points what each of the user's players scored that week,
     *        bench included; its keys are his roster that week
     */
    public static void played(WireMockServer sleeper, int week, List<String> starters,
            Map<String, Double> points) {
        String userLine = """
                {"roster_id": 1, "matchup_id": 1, "starters": [%s], "starters_points": [%s],
                 "players": [%s], "players_points": {%s}}
                """.formatted(quoted(starters),
                starters.stream().map(id -> String.valueOf(points.getOrDefault(id, 0.0)))
                        .collect(Collectors.joining(", ")),
                quoted(List.copyOf(points.keySet())),
                points.entrySet().stream()
                        .map(entry -> "\"%s\": %s".formatted(entry.getKey(), entry.getValue()))
                        .collect(Collectors.joining(", ")));
        String rivalLine = """
                {"roster_id": 2, "matchup_id": 1, "starters": ["4866"], "starters_points": [10],
                 "players": ["4866"], "players_points": {"4866": 10}}
                """;
        sleeper.stubFor(get(urlEqualTo(SleeperStubs.LEAGUE_PATH + "/matchups/" + week))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[" + userLine + "," + rivalLine + "]")));
    }

    private static String quoted(List<String> ids) {
        return ids.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(", "));
    }
}
