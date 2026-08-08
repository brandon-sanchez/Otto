package otto.snapshot;

import java.util.List;
import java.util.Map;

import otto.directory.PlayerHealth;

/**
 * One roster inside a Snapshot. The userRoster flag marks the roster
 * owned by the configured user; Alert detectors key off it. Health,
 * name, position, and team maps cover only players present in the
 * Player Directory. The compact constructor normalizes absent
 * collections, so Snapshots stored before a field existed still load.
 */
public record RosterSnapshot(
        int rosterId,
        String ownerId,
        String ownerName,
        boolean userRoster,
        List<String> starters,
        List<String> players,
        Map<String, PlayerHealth> playerHealth,
        Map<String, String> playerNames,
        Map<String, String> playerPositions,
        Map<String, String> playerTeams) {

    public RosterSnapshot {
        starters = starters == null ? List.of() : starters;
        players = players == null ? List.of() : players;
        playerHealth = playerHealth == null ? Map.of() : playerHealth;
        playerNames = playerNames == null ? Map.of() : playerNames;
        playerPositions = playerPositions == null ? Map.of() : playerPositions;
        playerTeams = playerTeams == null ? Map.of() : playerTeams;
    }
}
