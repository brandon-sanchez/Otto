package otto.alerts;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.events.DiffKind;
import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;
import otto.settings.Trigger;
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
     * status transition, or about a player another manager dropped, is
     * that player's news; anything else mutes the class of the problem
     * that led the message.
     */
    private String muteTarget(List<Event> alerts, String playerId) {
        List<String> problemKeys = alerts.stream()
                .map(event -> event.key().substring("alert:".length()))
                .toList();
        // A trade names several players, so muting it can only mean the
        // class: the user is saying he does not want trade news. A
        // Commissioner Edit that sent a player across is the same news
        // by another route, and it goes quiet with it.
        if (problemKeys.stream().anyMatch(key ->
                key.startsWith(DiffKind.TRADE.fact() + ":")
                        || key.startsWith(DiffKind.COMMISSIONER.fact() + ":"))) {
            return AlertCandidate.Source.TRADE.muteClass();
        }
        // News about one player - a status change, a drop, a Watchlist
        // hit, a last call before lock - mutes that player.
        boolean news = problemKeys.stream()
                .anyMatch(key -> key.startsWith("snapshot-diff:") || key.startsWith("final:")
                        || key.startsWith("watchlist:"));
        if (news && !playerId.isBlank()) {
            return MuteStore.playerTarget(playerId);
        }
        if (problemKeys.stream().anyMatch(key -> key.startsWith("legality:"))) {
            return AlertCandidate.Source.LEGALITY.muteClass();
        }
        if (problemKeys.stream().anyMatch(key -> key.startsWith("edge:"))) {
            return AlertCandidate.Source.EDGE.muteClass();
        }
        // A waiver board names five players, so muting it can only mean
        // the class: the user is saying he does not want the Tuesday
        // board, not that he is done with one of the targets on it.
        if (problemKeys.stream().anyMatch(key -> key.startsWith("waiver:"))) {
            return Trigger.WAIVER.muteTarget();
        }
        return playerId.isBlank()
                ? AlertCandidate.Source.LEGALITY.muteClass()
                : MuteStore.playerTarget(playerId);
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
