package otto.alerts;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import otto.directory.PlayerHealth;
import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;

/**
 * Reads the user's Ignore taps out of the Event Log. An Ignore
 * declines one Recommendation: the problem keys behind that Alert stop
 * producing follow-ups. It never protects an illegal lineup - legality
 * problems disregard this ledger - and a status worse than the one the
 * user waved off revives the problem.
 */
@Component
public class IgnoreLedger {

    private final EventLog eventLog;

    public IgnoreLedger(EventLog eventLog) {
        this.eventLog = eventLog;
    }

    /** The problem keys of every Alert the user has Ignored. */
    public Set<String> ignoredKeys() {
        Set<String> alertIds = new HashSet<>();
        for (Event event : eventLog.all()) {
            if (event.type() == EventType.USER_ACTION
                    && "ignore".equals(event.facts().get("action"))) {
                alertIds.add(event.facts().getOrDefault("alertId", ""));
            }
        }
        Set<String> keys = new HashSet<>();
        for (Event event : eventLog.all()) {
            if (event.type() == EventType.ALERT_SENT
                    && alertIds.contains(event.facts().get("alertId"))) {
                keys.add(event.key().substring("alert:".length()));
            }
        }
        return keys;
    }

    /**
     * True when the user's Ignore still covers this player at this
     * designation. A worse designation than the one on record at tap
     * time revives the problem, so it no longer suppresses.
     */
    public boolean covers(String playerId, PlayerHealth currentHealth) {
        Optional<Event> latest = eventLog.all().stream()
                .filter(event -> event.type() == EventType.USER_ACTION)
                .filter(event -> "ignore".equals(event.facts().get("action")))
                .filter(event -> playerId != null
                        && playerId.equals(event.facts().get("playerId")))
                .max(Comparator.comparing(Event::at));
        if (latest.isEmpty()) {
            return false;
        }
        String healthAtTap = latest.get().facts().get("healthAtTap");
        if (healthAtTap == null || currentHealth == null) {
            return true;
        }
        return !currentHealth.isWorseThan(PlayerHealth.valueOf(healthAtTap));
    }
}
