package otto.check;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import otto.directory.PlayerHealth;
import otto.lineup.GameWeek;
import otto.lineup.LeagueScoring;
import otto.lineup.PlayerPositions;
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
        Optional<SeasonWeek> seasonWeek,
        LeagueScoring scoring,
        Optional<ProjectionTable> projections,
        List<Slot> startingSlots,
        Optional<GameWeek> games) {

    public static WeekFacts unavailable(LeagueScoring scoring, List<Slot> startingSlots) {
        return new WeekFacts(Optional.empty(), scoring, Optional.empty(), startingSlots,
                Optional.empty());
    }

    public Optional<String> weekKey() {
        return seasonWeek.map(SeasonWeek::key);
    }

    public OptionalInt weekNumber() {
        return seasonWeek.stream().mapToInt(SeasonWeek::number).findFirst();
    }

    /**
     * Returns the player's lineup position, or null when Otto cannot tell
     * it. A kicker or defense, which the roster carries no position for,
     * is read from his id and this week's projection, so a K or DEF slot
     * sees him.
     */
    public String positionOf(RosterSnapshot roster, String playerId) {
        String known = roster.playerPositions().get(playerId);
        return projections.map(table -> table.positionOf(playerId, known))
                .orElseGet(() -> PlayerPositions.of(playerId, known, null))
                .orElse(null);
    }

    /**
     * Returns the lineup position of every roster player Otto can place,
     * as {@link #positionOf} reads it.
     */
    public Map<String, String> positions(RosterSnapshot roster) {
        Map<String, String> positions = new HashMap<>();
        for (String playerId : roster.players()) {
            String position = positionOf(roster, playerId);
            if (position != null) {
                positions.put(playerId, position);
            }
        }
        return Map.copyOf(positions);
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
