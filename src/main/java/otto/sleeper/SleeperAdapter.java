package otto.sleeper;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import otto.OttoProperties;

/**
 * Typed, schema-validated access to the Sleeper league endpoints.
 * Any drift from the expected shape becomes a typed unavailable result.
 */
@Component
public class SleeperAdapter {

    private final SleeperClient client;
    private final SleeperCache cache;
    private final String leaguePath;

    public SleeperAdapter(SleeperClient client, SleeperCache cache, OttoProperties properties) {
        this.client = client;
        this.cache = cache;
        this.leaguePath = "/v1/league/" + properties.leagueId();
    }

    public record League(String leagueId, String name, String status) {
    }

    public record Roster(int rosterId, String ownerId, List<String> players, List<String> starters) {
    }

    public record LeagueUser(String userId, String displayName) {
    }

    public SourceResult<League> league() {
        return fetch(leaguePath).flatMap(body -> {
            if (!body.hasNonNull("league_id") || !body.hasNonNull("status")) {
                return schemaDrift(leaguePath, "league_id or status missing");
            }
            return ok(new League(
                    body.get("league_id").asText(),
                    body.path("name").asText(null),
                    body.get("status").asText()));
        });
    }

    public SourceResult<List<Roster>> rosters() {
        String path = leaguePath + "/rosters";
        return fetch(path).flatMap(body -> {
            if (!body.isArray()) {
                return schemaDrift(path, "rosters is not an array");
            }
            List<Roster> rosters = new ArrayList<>();
            for (JsonNode roster : body) {
                if (!roster.hasNonNull("roster_id") || !roster.hasNonNull("owner_id")) {
                    return schemaDrift(path, "roster_id or owner_id missing");
                }
                rosters.add(new Roster(
                        roster.get("roster_id").asInt(),
                        roster.get("owner_id").asText(),
                        textList(roster.path("players")),
                        textList(roster.path("starters"))));
            }
            return ok(rosters);
        });
    }

    public SourceResult<List<LeagueUser>> users() {
        String path = leaguePath + "/users";
        return fetch(path).flatMap(body -> {
            if (!body.isArray()) {
                return schemaDrift(path, "users is not an array");
            }
            List<LeagueUser> users = new ArrayList<>();
            for (JsonNode user : body) {
                if (!user.hasNonNull("user_id")) {
                    return schemaDrift(path, "user_id missing");
                }
                users.add(new LeagueUser(
                        user.get("user_id").asText(),
                        user.path("display_name").asText(null)));
            }
            return ok(users);
        });
    }

    private SourceResult<JsonNode> fetch(String path) {
        String knownEtag = cache.get(path).map(SleeperCache.Entry::etag).orElse(null);
        return switch (client.get(path, knownEtag)) {
            case SourceResult.Unavailable<SleeperClient.Fetched> unavailable ->
                new SourceResult.Unavailable<>(unavailable.source(), unavailable.reason());
            case SourceResult.Ok<SleeperClient.Fetched> ok -> {
                if (ok.value().notModified()) {
                    yield cache.get(path)
                            .<SourceResult<JsonNode>>map(entry -> new SourceResult.Ok<>(entry.body()))
                            .orElseGet(() -> new SourceResult.Unavailable<>(
                                    "sleeper:" + path, "304 without a cached body"));
                }
                cache.put(path, ok.value().etag(), ok.value().body());
                yield new SourceResult.Ok<>(ok.value().body());
            }
        };
    }

    private static <T> SourceResult<T> ok(T value) {
        return new SourceResult.Ok<>(value);
    }

    private static <T> SourceResult<T> schemaDrift(String path, String reason) {
        return new SourceResult.Unavailable<>("sleeper:" + path, "schema drift: " + reason);
    }

    private static List<String> textList(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array.isArray()) {
            array.forEach(node -> {
                if (!node.isNull()) {
                    values.add(node.asText());
                }
            });
        }
        return values;
    }
}
