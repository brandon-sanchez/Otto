package otto.alerts;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;
import otto.snapshot.RosterSnapshot;
import otto.snapshot.Snapshot;
import otto.snapshot.SnapshotStore;

/**
 * Records one button tap under an Alert as a USER_ACTION event. The
 * tap references the Alert's short id; the recorded facts capture what
 * the roster looked like at tap time, so later Checks can tell whether
 * anything visibly changed since the user answered.
 */
@Component
public class AlertActions {

    private final EventLog eventLog;
    private final SnapshotStore snapshotStore;
    private final MuteStore muteStore;
    private final Clock clock;

    public AlertActions(EventLog eventLog, SnapshotStore snapshotStore,
            MuteStore muteStore, Clock clock) {
        this.eventLog = eventLog;
        this.snapshotStore = snapshotStore;
        this.muteStore = muteStore;
        this.clock = clock;
    }

    /**
     * Applies one tap to the Alert it references.
     *
     * @return the ack text for the tap, or empty when no Alert with
     *         that id is on record
     */
    public Optional<String> apply(String action, long alertId) {
        List<Event> alerts = alertEvents(alertId);
        if (alerts.isEmpty()) {
            return Optional.empty();
        }
        Instant now = clock.instant();

        Map<String, String> facts = new HashMap<>();
        facts.put("action", action);
        facts.put("alertId", String.valueOf(alertId));
        facts.put("playerId", firstFact(alerts, "playerId"));
        facts.put("player", firstFact(alerts, "player"));
        rosterAtTap().ifPresent(roster -> {
            facts.put("startersAtTap", String.join(",", roster.starters()));
            String playerId = facts.get("playerId");
            if (!playerId.isBlank() && roster.playerHealth().get(playerId) != null) {
                facts.put("healthAtTap", roster.playerHealth().get(playerId).name());
            }
        });
        if ("mute".equals(action)) {
            String target = muteTarget(alerts, facts.get("playerId"));
            facts.put("muteTarget", target);
            muteStore.mute(target, now);
        }

        eventLog.append(new Event("action:%s:%d".formatted(action, alertId),
                EventType.USER_ACTION, now, Map.copyOf(facts)));
        return Optional.of(ackText(action));
    }

    /**
     * What the Mute button under this Alert silences. An Alert about a
     * status transition is that player's news; anything else mutes the
     * class of the problem that led the message.
     */
    private String muteTarget(List<Event> alerts, String playerId) {
        List<String> problemKeys = alerts.stream()
                .map(event -> event.key().substring("alert:".length()))
                .toList();
        boolean news = problemKeys.stream()
                .anyMatch(key -> key.startsWith("snapshot-diff:") || key.startsWith("final:"));
        if (news && !playerId.isBlank()) {
            return "player:" + playerId;
        }
        if (problemKeys.stream().anyMatch(key -> key.startsWith("legality:"))) {
            return "class:legality";
        }
        if (problemKeys.stream().anyMatch(key -> key.startsWith("edge:"))) {
            return "class:edge";
        }
        return playerId.isBlank() ? "class:legality" : "player:" + playerId;
    }

    private List<Event> alertEvents(long alertId) {
        return eventLog.all().stream()
                .filter(event -> event.type() == EventType.ALERT_SENT)
                .filter(event -> String.valueOf(alertId).equals(event.facts().get("alertId")))
                .toList();
    }

    private String firstFact(List<Event> alerts, String name) {
        return alerts.stream()
                .map(event -> event.facts().get(name))
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
    }

    private Optional<RosterSnapshot> rosterAtTap() {
        return snapshotStore.current()
                .map(Snapshot::rosters)
                .flatMap(rosters -> rosters.stream()
                        .filter(RosterSnapshot::userRoster)
                        .findFirst());
    }

    private String ackText(String action) {
        return switch (action) {
            case "done" -> "Got it. I will confirm once I see the problem is gone.";
            case "ignore" -> "Understood. I will drop this recommendation.";
            case "mute" -> "Muted. I will stay quiet about this until you unmute it.";
            default -> "Got it.";
        };
    }
}
