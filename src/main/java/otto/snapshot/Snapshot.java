package otto.snapshot;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * An immutable point-in-time capture of league state. All proactive
 * behavior is driven by the diff between two consecutive Snapshots.
 */
public record Snapshot(
        Instant at,
        LeagueStatus leagueStatus,
        List<RosterSnapshot> rosters) {

    /** Returns the user's own roster, or empty when no team in the league is his. */
    public Optional<RosterSnapshot> userRoster() {
        return rosters.stream().filter(RosterSnapshot::userRoster).findFirst();
    }
}
