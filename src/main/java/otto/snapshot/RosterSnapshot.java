package otto.snapshot;

import java.util.List;
import java.util.Map;

import otto.directory.PlayerHealth;

/**
 * One roster inside a Snapshot. The userRoster flag marks the roster
 * owned by the configured user; Alert detectors key off it. Health and
 * name maps cover only players present in the Player Directory.
 */
public record RosterSnapshot(
        int rosterId,
        String ownerId,
        String ownerName,
        boolean userRoster,
        List<String> starters,
        List<String> players,
        Map<String, PlayerHealth> playerHealth,
        Map<String, String> playerNames) {
}
