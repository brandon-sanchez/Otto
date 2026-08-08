package otto.snapshot;

import java.time.Instant;
import java.util.List;

/**
 * An immutable point-in-time capture of league state. All proactive
 * behavior is driven by the diff between two consecutive Snapshots.
 */
public record Snapshot(
        Instant at,
        LeagueStatus leagueStatus,
        List<RosterSnapshot> rosters) {
}
