package otto.snapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.directory.PlayerHealth;
import otto.events.Event;
import otto.events.EventType;

/**
 * Computes Snapshot Diff events between two consecutive Snapshots:
 * status transitions on rostered players. The first Snapshot has no
 * predecessor, so it never produces events.
 */
@Component
public class SnapshotDiffer {

    public List<Event> diff(Optional<Snapshot> previous, Snapshot current, Instant at) {
        if (previous.isEmpty()) {
            return List.of();
        }
        List<Event> events = new ArrayList<>();
        Map<Integer, RosterSnapshot> previousRosters = byRosterId(previous.get());
        for (RosterSnapshot roster : current.rosters()) {
            RosterSnapshot before = previousRosters.get(roster.rosterId());
            if (before == null) {
                continue;
            }
            roster.playerHealth().forEach((playerId, health) -> {
                PlayerHealth healthBefore = before.playerHealth().get(playerId);
                if (healthBefore == null || healthBefore == health) {
                    return;
                }
                events.add(new Event(
                        "snapshot-diff:status:%s:%s->%s".formatted(playerId, healthBefore, health),
                        EventType.SNAPSHOT_DIFF,
                        at,
                        Map.of(
                                "playerId", playerId,
                                "player", roster.playerNames().getOrDefault(playerId, playerId),
                                "from", healthBefore.name(),
                                "to", health.name(),
                                "starter", String.valueOf(roster.starters().contains(playerId)),
                                "userRoster", String.valueOf(roster.userRoster()))));
            });
        }
        return events;
    }

    private static Map<Integer, RosterSnapshot> byRosterId(Snapshot snapshot) {
        return snapshot.rosters().stream()
                .collect(java.util.stream.Collectors.toMap(RosterSnapshot::rosterId, roster -> roster));
    }
}
