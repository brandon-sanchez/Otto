package otto.alerts;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import otto.events.DiffKind;
import otto.events.Event;
import otto.events.EventType;
import otto.watchlist.WatchlistStore;

/**
 * Turns what happened to a Watchlist player into an Alert candidate:
 * he was dropped, another manager added him, the rest of the fantasy
 * world started claiming him, or this week's projection moved.
 *
 * A Snipe is the one that always Alerts. It rides at High confidence
 * because nothing about it is an estimate - the player is gone, and the
 * plan the user had for him is over. The other three are news about a
 * player the user only watches, so they ride at Medium and voice what
 * is still unknown.
 */
@Component
public class WatchlistDetector {

    private static final String TRENDING = "watchlist:trending:";
    private static final String PROJECTION = "watchlist:projection:";

    /**
     * A Watchlist candidate keys off the event it came from, behind a
     * prefix of its own. League activity reports the same drop under
     * the bare event key, and two candidates for one event must stay
     * tellable apart in the Event Log - they merge into one message,
     * but each has to record that it fired.
     */
    private static final String KEY_PREFIX = "watchlist:";

    private final WatchlistStore watchlist;

    public WatchlistDetector(WatchlistStore watchlist) {
        this.watchlist = watchlist;
    }

    /**
     * The candidates among these events. The Watchlist is read once for
     * the whole batch, because the Event Log window it runs over holds
     * far more events than the list holds players.
     */
    public List<AlertCandidate> detect(List<Event> events) {
        Set<String> watched = watchlist.playerIds();
        if (watched.isEmpty()) {
            return List.of();
        }
        return events.stream()
                .flatMap(event -> detect(event, watched).stream())
                .toList();
    }

    private Optional<AlertCandidate> detect(Event event, Set<String> watched) {
        if (event.type() != EventType.SNAPSHOT_DIFF
                && event.type() != EventType.WATCHLIST_MOVE) {
            return Optional.empty();
        }
        String playerId = event.facts().get("playerId");
        if (playerId == null || !watched.contains(playerId)) {
            return Optional.empty();
        }
        String player = event.facts().getOrDefault("player", playerId);
        String key = event.key();

        Recommendation recommendation;
        if (event.type() == EventType.WATCHLIST_MOVE) {
            if (key.startsWith(TRENDING)) {
                recommendation = trending(playerId, player, event.facts());
            } else if (key.startsWith(PROJECTION)) {
                recommendation = projectionMoved(playerId, player, event.facts());
            } else {
                return Optional.empty();
            }
        } else {
            // The user's own move is not news to him: he made it, and a
            // player he just claimed is the plan working, not a Snipe.
            if ("true".equals(event.facts().get("userRoster"))) {
                return Optional.empty();
            }
            recommendation = switch (DiffKind.of(event)) {
                case DROP -> dropped(playerId, player, event.facts());
                case ADD -> sniped(playerId, player, event.facts());
                // A trade between two other managers does not change
                // whether the user can have him: he was unreachable
                // before and is unreachable now.
                case TRADE, STATUS -> null;
            };
            if (recommendation == null) {
                return Optional.empty();
            }
        }

        // Only a shared event needs the prefix. A Watchlist move is read
        // by nobody else, so it keys off itself.
        return Optional.of(new AlertCandidate(
                AlertCandidate.Source.WATCHLIST,
                event.type() == EventType.WATCHLIST_MOVE ? key : KEY_PREFIX + key,
                playerId,
                event.facts().getOrDefault("team", ""),
                recommendation,
                new HashMap<>(event.facts())));
    }

    private Recommendation dropped(String playerId, String player, Map<String, String> facts) {
        String owner = manager(facts, "fromManager");
        return new Recommendation(
                playerId,
                player,
                "%s is a free agent again: %s dropped him".formatted(player, owner),
                Confidence.MEDIUM,
                List.of(
                        "%s is on your watchlist and nobody rosters him now".formatted(player),
                        "A claim reaches him before the rest of the league notices"),
                List.of("A manager who drops a player has usually seen his role shrink"));
    }

    private Recommendation sniped(String playerId, String player, Map<String, String> facts) {
        String owner = manager(facts, "toManager");
        return new Recommendation(
                playerId,
                player,
                "%s added %s: your watchlist plan for him is over".formatted(owner, player),
                Confidence.HIGH,
                List.of(
                        "%s is on your watchlist and is now rostered by %s".formatted(
                                player, owner),
                        "A trade is the only way to reach him while %s holds him"
                                .formatted(owner)),
                List.of("He reaches waivers again if %s drops him".formatted(owner)));
    }

    private Recommendation trending(String playerId, String player, Map<String, String> facts) {
        String adds = facts.getOrDefault("adds", "0");
        String hours = facts.getOrDefault("lookbackHours", "24");
        String limit = facts.getOrDefault("trendingLimit", "25");
        return new Recommendation(
                playerId,
                player,
                "%s is trending up hard: %s adds across Sleeper in %s hours".formatted(
                        player, adds, hours),
                Confidence.MEDIUM,
                List.of(
                        "%s is on your watchlist and just entered Sleeper's top %s adds"
                                .formatted(player, limit),
                        "Managers everywhere are claiming him, so he may not last"),
                List.of("An add count measures attention, not production"));
    }

    private Recommendation projectionMoved(String playerId, String player,
            Map<String, String> facts) {
        String was = facts.getOrDefault("was", "");
        String now = facts.getOrDefault("now", "");
        String move = facts.getOrDefault("move", "");
        String week = facts.getOrDefault("week", "this week");
        return new Recommendation(
                playerId,
                player,
                "%s now projects %s points in %s, from %s (%s)".formatted(
                        player, now, week, was, move),
                Confidence.MEDIUM,
                List.of(
                        "%s is on your watchlist and his %s projection moved %s points"
                                .formatted(player, week, move),
                        "The number is priced in your league's own scoring"),
                List.of("A projection is an estimate and can move again before kickoff"));
    }

    /** The manager named by one of the transaction facts, or a stand-in. */
    private static String manager(Map<String, String> facts, String fact) {
        String manager = facts.get(fact);
        return manager == null || manager.isBlank() ? "another manager" : manager;
    }
}
