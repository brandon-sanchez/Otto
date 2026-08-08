package otto.events;

import java.time.Instant;
import java.util.Map;

/**
 * One Snapshot Diff or pipeline event in the season-long Event Log.
 * The key identifies the fact; an exact duplicate key is dropped.
 */
public record Event(
        String key,
        EventType type,
        Instant at,
        Map<String, String> facts) {
}
