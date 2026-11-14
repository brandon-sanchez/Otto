package otto.directory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;
import otto.sleeper.SleeperClient;
import otto.sleeper.SourceResult;

/**
 * Keeps the Player Directory current with a conditional GET against
 * Sleeper's players file on the configured check interval. The file is
 * a multi-megabyte download, so an unchanged ETag skips the body and
 * only refreshes the checked-at time.
 */
@Component
public class PlayerDirectoryService {

    private static final String PLAYERS_PATH = "/v1/players/nfl";
    private static final Set<String> KEPT_POSITIONS = Set.of("QB", "RB", "WR", "TE");

    /** Every letter Sleeper's players file writes for a defender. */
    private static final Set<String> DEFENSIVE_POSITIONS = Set.of(
            "DL", "DE", "DT", "NT", "LB", "OLB", "ILB", "DB", "CB", "S", "SS", "FS");

    private static final Predicate<JsonNode> OFFENSE =
            player -> KEPT_POSITIONS.contains(player.path("position").asText());

    private static final Predicate<JsonNode> DEFENDER_ON_A_TEAM =
            player -> DEFENSIVE_POSITIONS.contains(player.path("position").asText())
                    && player.hasNonNull("team");

    private final SleeperClient sleeper;
    private final PlayerDirectoryStore store;
    private final EventLog eventLog;
    private final Clock clock;
    private final Duration checkInterval;

    private enum RefreshMode {
        IF_DUE,
        FULL
    }

    public PlayerDirectoryService(SleeperClient sleeper, PlayerDirectoryStore store,
            EventLog eventLog, Clock clock, OttoProperties properties) {
        this.sleeper = sleeper;
        this.store = store;
        this.eventLog = eventLog;
        this.clock = clock;
        this.checkInterval = properties.directoryCheckInterval();
    }

    public sealed interface Update {

        record Skipped() implements Update {
        }

        record Current(PlayerDirectory directory) implements Update {
        }

        record Unavailable(String source, String reason) implements Update {
        }
    }

    /**
     * Runs the players-file check when the check interval has elapsed
     * since the stored directory was last checked; skips otherwise.
     */
    public synchronized Update updateIfDue() {
        return update(RefreshMode.IF_DUE);
    }

    /** Downloads the full directory even when the stored copy was checked recently. */
    public synchronized Update refreshNow() {
        return update(RefreshMode.FULL);
    }

    private Update update(RefreshMode mode) {
        Instant now = clock.instant();
        Optional<PlayerDirectory> existing = store.read();
        if (mode == RefreshMode.IF_DUE && existing.isPresent()
                && Duration.between(existing.get().checkedAt(), now).compareTo(checkInterval) < 0) {
            return new Update.Skipped();
        }

        String knownEtag = mode == RefreshMode.FULL
                ? null
                : existing.map(PlayerDirectory::etag).orElse(null);
        SourceResult<SleeperClient.Fetched> result = sleeper.get(PLAYERS_PATH, knownEtag);
        return switch (result) {
            case SourceResult.Unavailable<SleeperClient.Fetched> unavailable ->
                new Update.Unavailable(unavailable.source(), unavailable.reason());
            case SourceResult.Ok<SleeperClient.Fetched> ok -> {
                if (ok.value().notModified() && existing.isEmpty()) {
                    yield new Update.Unavailable("sleeper:" + PLAYERS_PATH,
                            "304 without a stored directory");
                }
                PlayerDirectory updated;
                if (ok.value().notModified()) {
                    updated = existing.orElseThrow().withCheckedAt(now);
                } else {
                    JsonNode playersFile = ok.value().body();
                    updated = new PlayerDirectory(ok.value().etag(), now,
                            trim(playersFile, OFFENSE), trim(playersFile, DEFENDER_ON_A_TEAM));
                    existing.ifPresent(previous -> emitStatusEvents(previous, updated, now));
                }
                store.write(updated);
                yield new Update.Current(updated);
            }
        };
    }

    private void emitStatusEvents(PlayerDirectory previous, PlayerDirectory current, Instant now) {
        current.players().forEach((playerId, player) -> {
            DirectoryPlayer before = previous.players().get(playerId);
            if (before == null || before.health() == player.health()) {
                return;
            }
            String key = "player-status:%s:%s->%s"
                    .formatted(playerId, before.health(), player.health());
            eventLog.append(new Event(key, EventType.PLAYER_STATUS_CHANGE, now, Map.of(
                    "player", player.fullName(),
                    "playerId", playerId,
                    "from", before.health().name(),
                    "to", player.health().name())));
        });
    }

    private static Map<String, DirectoryPlayer> trim(JsonNode playersFile,
            Predicate<JsonNode> kept) {
        Map<String, DirectoryPlayer> players = new HashMap<>();
        playersFile.properties().forEach(entry -> {
            JsonNode player = entry.getValue();
            if (!kept.test(player)) {
                return;
            }
            players.put(entry.getKey(), new DirectoryPlayer(
                    entry.getKey(),
                    player.path("full_name").asText(),
                    player.path("position").asText(),
                    player.path("team").asText(null),
                    PlayerHealth.from(
                            player.path("status").asText(null),
                            player.path("injury_status").asText(null))));
        });
        return players;
    }
}
