package otto.sleeper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import otto.OttoProperties;

/**
 * Typed, schema-validated access to the Sleeper league endpoints.
 * Any drift from the expected shape becomes a typed unavailable result.
 *
 * Every read is a conditional GET by default, which is what the Check
 * needs: its whole job is to poll at the Freshness Ceiling.
 * {@link #cachedWithin(Duration)} hands back the same wire with a
 * cadence gate in front, for readers that must not add polling of their
 * own.
 */
@Component
public class SleeperAdapter {

    private final SleeperClient client;
    private final SleeperCache cache;
    private final Clock clock;
    private final String leaguePath;

    /** How stale a cached body may be before this reader refetches. */
    private final Duration maxAge;

    @Autowired
    public SleeperAdapter(SleeperClient client, SleeperCache cache, Clock clock,
            OttoProperties properties) {
        this(client, cache, clock, "/v1/league/" + properties.leagueId(), Duration.ZERO);
    }

    private SleeperAdapter(SleeperClient client, SleeperCache cache, Clock clock,
            String leaguePath, Duration maxAge) {
        this.client = client;
        this.cache = cache;
        this.clock = clock;
        this.leaguePath = leaguePath;
        this.maxAge = maxAge;
    }

    /**
     * A reader over the same wire and the same cache that answers from
     * the stored copy while it is younger than maxAge. An Ask reads this
     * way: the Check keeps the cache filled every minute, and nothing
     * can be fresher than the Freshness Ceiling, so a burst of questions
     * costs no requests.
     */
    public SleeperAdapter cachedWithin(Duration maxAge) {
        return new SleeperAdapter(client, cache, clock, leaguePath, maxAge);
    }

    public record League(String leagueId, String name, String status,
            List<String> rosterPositions, Map<String, Double> scoringSettings) {
    }

    public record NflState(String season, int week) {
    }

    /** One scheduled game; players lock at startTime. */
    public record Game(String homeTeam, String awayTeam, Instant startTime, String status) {
    }

    /**
     * One team in the league. A team nobody has claimed yet has no
     * owner, which is how every team reads before a draft fills the
     * league up.
     */
    public record Roster(int rosterId, Optional<String> ownerId, List<String> players,
            List<String> starters) {
    }

    public record LeagueUser(String userId, String displayName) {
    }

    /** One item from a player's news feed, as Sleeper publishes it. */
    public record NewsItem(String headline, String detail, String analysis, String source,
            Instant published, String url) {
    }

    public SourceResult<League> league() {
        return fetch(leaguePath).flatMap(body -> {
            if (!body.hasNonNull("league_id") || !body.hasNonNull("status")) {
                return schemaDrift(leaguePath, "league_id or status missing");
            }
            return ok(new League(
                    body.get("league_id").asText(),
                    body.path("name").asText(null),
                    body.get("status").asText(),
                    textList(body.path("roster_positions")),
                    numberMap(body.path("scoring_settings"))));
        });
    }

    public SourceResult<NflState> nflState() {
        String path = "/v1/state/nfl";
        return fetch(path).flatMap(body -> {
            JsonNode season = body.get("season");
            JsonNode week = body.get("week");
            if (season == null || !season.isTextual() || season.asText().isBlank()
                    || week == null || !week.isIntegralNumber() || week.asInt() < 1) {
                return schemaDrift(path, "season or week missing or malformed");
            }
            return ok(new NflState(season.asText(), week.asInt()));
        });
    }

    /**
     * Per-player projected stat lines for one week. All points compute
     * in Java as stat line times league scoring; Sleeper's pre-computed
     * points fields ride along in the map but are never used, because
     * no league scoring setting carries their key.
     */
    public SourceResult<Map<String, Map<String, Double>>> projections(String season, int week) {
        String path = "/v1/projections/nfl/regular/%s/%d".formatted(season, week);
        return fetch(path).flatMap(body -> {
            if (!body.isObject()) {
                return schemaDrift(path, "projections is not an object");
            }
            Map<String, Map<String, Double>> statLines = new HashMap<>();
            body.properties().forEach(entry -> {
                if (entry.getValue().isObject()) {
                    statLines.put(entry.getKey(), numberMap(entry.getValue()));
                }
            });
            return ok(statLines);
        });
    }

    /** The week's games with kickoff times; a team with no game is on bye. */
    public SourceResult<List<Game>> games(String season, int week) {
        String path = "/scores/nfl/regular/%s/%d".formatted(season, week);
        return fetch(path).flatMap(body -> {
            if (!body.isArray() || body.isEmpty()) {
                return schemaDrift(path, "games is not a non-empty array");
            }
            List<Game> games = new ArrayList<>();
            for (JsonNode game : body) {
                JsonNode metadata = game.path("metadata");
                // A drifted start_time must fail loudly here: a non-numeric
                // value silently read as 0 would put every lock in 1970 and
                // suppress every Alert as "after lock".
                JsonNode startTime = game.get("start_time");
                if (startTime == null || !startTime.isIntegralNumber() || startTime.asLong() <= 0
                        || !metadata.hasNonNull("home_team")
                        || !metadata.hasNonNull("away_team")) {
                    return schemaDrift(path, "start_time or home/away team missing or malformed");
                }
                games.add(new Game(
                        metadata.get("home_team").asText(),
                        metadata.get("away_team").asText(),
                        Instant.ofEpochMilli(startTime.asLong()),
                        game.path("status").asText(null)));
            }
            return ok(games);
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
                // Only roster_id is required: it is how Otto tells one
                // team from another. An absent owner means nobody has
                // claimed the team yet, which is a real state Sleeper
                // reports for most teams before a draft.
                if (!roster.hasNonNull("roster_id")) {
                    return schemaDrift(path, "roster_id missing");
                }
                rosters.add(new Roster(
                        roster.get("roster_id").asInt(),
                        Optional.ofNullable(roster.get("owner_id"))
                                .filter(JsonNode::isTextual)
                                .map(JsonNode::asText),
                        textList(roster.path("players")),
                        textList(roster.path("starters"))));
            }
            return ok(rosters);
        });
    }

    /**
     * Recent news for one player, newest first.
     *
     * News is the one Sleeper source that is always fetched live and
     * never cached: the user asks for it precisely when something just
     * happened, and a minute-old answer would be the wrong one.
     *
     * @param limit how many items to ask Sleeper for
     */
    public SourceResult<List<NewsItem>> playerNews(String playerId, int limit) {
        String path = "/players/nfl/%s/news?limit=%d".formatted(playerId, limit);
        return client.get(path, null).flatMap(fetched -> {
            JsonNode body = fetched.body();
            if (body == null || !body.isArray()) {
                return schemaDrift(path, "news is not an array");
            }
            List<NewsItem> items = new ArrayList<>();
            for (JsonNode item : body) {
                JsonNode metadata = item.path("metadata");
                JsonNode published = item.get("published");
                items.add(new NewsItem(
                        metadata.path("title").asText(null),
                        metadata.path("description").asText(null),
                        metadata.path("analysis").asText(null),
                        item.path("source").asText(null),
                        published != null && published.isIntegralNumber()
                                ? Instant.ofEpochMilli(published.asLong())
                                : null,
                        metadata.path("url").asText(null)));
            }
            return ok(items);
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
        Instant now = clock.instant();
        Optional<JsonNode> cached = cache.fresh(path, maxAge, now);
        if (cached.isPresent()) {
            return new SourceResult.Ok<>(cached.get());
        }
        String knownEtag = cache.get(path).map(SleeperCache.Entry::etag).orElse(null);
        return switch (client.get(path, knownEtag)) {
            case SourceResult.Unavailable<SleeperClient.Fetched> unavailable ->
                new SourceResult.Unavailable<>(unavailable.source(), unavailable.reason());
            case SourceResult.Ok<SleeperClient.Fetched> ok -> {
                if (ok.value().notModified()) {
                    yield cache.get(path)
                            .<SourceResult<JsonNode>>map(entry -> {
                                // A 304 confirms the stored copy: it is
                                // current as of now, not as of its download.
                                cache.put(path, entry.etag(), entry.body(), now);
                                return new SourceResult.Ok<>(entry.body());
                            })
                            .orElseGet(() -> new SourceResult.Unavailable<>(
                                    "sleeper:" + path, "304 without a cached body"));
                }
                cache.put(path, ok.value().etag(), ok.value().body(), now);
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

    private static Map<String, Double> numberMap(JsonNode object) {
        Map<String, Double> values = new HashMap<>();
        if (object.isObject()) {
            object.properties().forEach(entry -> {
                if (entry.getValue().isNumber()) {
                    values.put(entry.getKey(), entry.getValue().asDouble());
                }
            });
        }
        return values;
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
