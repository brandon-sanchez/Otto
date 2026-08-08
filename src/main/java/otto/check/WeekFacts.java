package otto.check;

import java.util.List;
import java.util.Optional;

import otto.directory.PlayerHealth;
import otto.lineup.GameWeek;
import otto.lineup.ProjectionTable;
import otto.lineup.Slot;
import otto.snapshot.RosterSnapshot;

/**
 * The week-scoped facts a Check hands to Alert detection: which week it
 * is, the projected stat lines priced in league scoring, the starting
 * slots, and the week's games. Every part is optional because every
 * source fails soft - a detector that misses its inputs skips instead
 * of guessing.
 */
public record WeekFacts(
        Optional<String> weekKey,
        Optional<ProjectionTable> projections,
        List<Slot> startingSlots,
        Optional<GameWeek> games) {

    public static WeekFacts unavailable(List<Slot> startingSlots) {
        return new WeekFacts(Optional.empty(), Optional.empty(), startingSlots, Optional.empty());
    }

    /**
     * True when the player can take the field this week: not ruled
     * out by designation and not on bye. Without game data the answer
     * is lenient. Lock timing is the caller's rule, not part of this.
     */
    public boolean canPlay(RosterSnapshot roster, String playerId) {
        PlayerHealth health = roster.playerHealth().get(playerId);
        if (health != null && health.rulesOutPlaying()) {
            return false;
        }
        String team = roster.playerTeams().get(playerId);
        return games.map(gameWeek -> !gameWeek.onBye(team)).orElse(true);
    }
}
