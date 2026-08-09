package otto.check;

import java.util.List;
import java.util.Optional;

import otto.directory.PlayerHealth;
import otto.lineup.GameWeek;
import otto.lineup.LeagueScoring;
import otto.lineup.ProjectionTable;
import otto.lineup.Slot;
import otto.snapshot.RosterSnapshot;

/**
 * The week-scoped facts a Check hands to Alert detection: which week it
 * is, how the league prices a stat line, the projected stat lines
 * priced that way, the starting slots, and the week's games. Every
 * optional part is optional because every source fails soft - a
 * detector that misses its inputs skips instead of guessing.
 *
 * The scoring rides along rather than staying inside the projection
 * table because a played week prices through it too: what a defense
 * allowed, and what a player has actually scored, are the same
 * arithmetic as a projection.
 */
public record WeekFacts(
        Optional<String> weekKey,
        LeagueScoring scoring,
        Optional<ProjectionTable> projections,
        List<Slot> startingSlots,
        Optional<GameWeek> games) {

    public static WeekFacts unavailable(LeagueScoring scoring, List<Slot> startingSlots) {
        return new WeekFacts(Optional.empty(), scoring, Optional.empty(), startingSlots,
                Optional.empty());
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
