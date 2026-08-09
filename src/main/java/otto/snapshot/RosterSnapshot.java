package otto.snapshot;

import java.util.List;
import java.util.Map;

import otto.directory.PlayerHealth;
import otto.sleeper.SleeperAdapter;

/**
 * One roster inside a Snapshot. The userRoster flag marks the roster
 * owned by the configured user; Alert detectors key off it. Health,
 * name, position, and team maps cover only players present in the
 * Player Directory. The compact constructor normalizes absent
 * collections, so Snapshots stored before a field existed still load.
 *
 * @param teamRecord what this team has won and lost so far
 * @param waiverBudgetUsed the FAAB this team has spent so far, which is
 *        what the waiver board subtracts from the league budget
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
        Map<String, String> playerTeams,
        SleeperAdapter.TeamRecord teamRecord,
        int waiverBudgetUsed) {

    public RosterSnapshot {
        starters = starters == null ? List.of() : starters;
        players = players == null ? List.of() : players;
        playerHealth = playerHealth == null ? Map.of() : playerHealth;
        playerNames = playerNames == null ? Map.of() : playerNames;
        playerPositions = playerPositions == null ? Map.of() : playerPositions;
        playerTeams = playerTeams == null ? Map.of() : playerTeams;
        teamRecord = teamRecord == null ? SleeperAdapter.TeamRecord.NONE : teamRecord;
    }

    /**
     * What to call this team. The owner's display name where there is
     * one; the owner id where a claimed team has no name Otto could
     * read, because a claimed team is somebody's and must not read as
     * empty; and the team number only when nobody has claimed it.
     */
    public String manager() {
        if (ownerName != null && !ownerName.isBlank()) {
            return ownerName;
        }
        if (ownerId != null && !ownerId.isBlank()) {
            return ownerId;
        }
        return unclaimedName(rosterId);
    }

    /** What a team is called before anyone has claimed it. */
    public static String unclaimedName(int rosterId) {
        return "team " + rosterId;
    }
}
