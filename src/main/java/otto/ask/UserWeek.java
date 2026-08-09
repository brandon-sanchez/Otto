package otto.ask;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import otto.check.WeekFacts;
import otto.directory.PlayerHealth;
import otto.lineup.ProjectionTable;
import otto.lineup.Slot;
import otto.sleeper.SleeperAdapter;
import otto.snapshot.RosterSnapshot;

/**
 * What an Ask tool needs to answer a question about one team: the
 * league document, that team's roster from the latest Snapshot, and
 * this week's facts. Usually the team is the user's, which is what the
 * lineup tools are for; a league question builds the same view over a
 * league mate's roster, because a trade has two sides and both must be
 * read the same way.
 *
 * It answers about one player at a time, so callers ask it rather than
 * walking into the roster and the week themselves. Every source fails
 * soft, so each answer states what it does when its source is missing.
 */
public record UserWeek(
        SleeperAdapter.League league,
        RosterSnapshot roster,
        WeekFacts week) {

    /** Sleeper writes an unfilled starting slot as player id "0". */
    private static final String EMPTY_SLOT = "0";

    public List<Slot> slots() {
        return week.startingSlots();
    }

    public Optional<String> weekKey() {
        return week.weekKey();
    }

    /** True when the week carries the projections and slots the math needs. */
    public boolean hasLineupMath() {
        return week.projections().isPresent() && !week.startingSlots().isEmpty();
    }

    public String name(String playerId) {
        return roster.playerNames().getOrDefault(playerId, playerId);
    }

    public String position(String playerId) {
        return roster.playerPositions().get(playerId);
    }

    public String team(String playerId) {
        return roster.playerTeams().get(playerId);
    }

    /** An unlisted player counts as healthy: only a designation flags one. */
    public PlayerHealth health(String playerId) {
        PlayerHealth health = roster.playerHealth().get(playerId);
        return health == null ? PlayerHealth.ACTIVE : health;
    }

    /** True when the player can take the field this week. */
    public boolean canPlay(String playerId) {
        return week.canPlay(roster, playerId);
    }

    /** True once the player's game has kicked off; without games, never. */
    public boolean locked(String playerId, Instant now) {
        return week.games().map(games -> games.locked(team(playerId), now)).orElse(false);
    }

    public boolean onBye(String playerId) {
        return week.games().map(games -> games.onBye(team(playerId))).orElse(false);
    }

    public Optional<Double> points(String playerId) {
        return week.projections().flatMap(table -> table.points(playerId, position(playerId)));
    }

    public String projection(String playerId) {
        return week.projections()
                .map(table -> table.display(playerId, position(playerId)))
                .orElse(ProjectionTable.NO_PROJECTION);
    }

    /** The starter in a starting slot, or null when the slot is empty. */
    public String starterAt(int index) {
        if (index < 0 || index >= roster.starters().size()) {
            return null;
        }
        String playerId = roster.starters().get(index);
        return playerId == null || EMPTY_SLOT.equals(playerId) ? null : playerId;
    }

    public List<String> starters() {
        return roster.starters();
    }

    public List<String> bench() {
        return roster.players().stream()
                .filter(playerId -> !roster.starters().contains(playerId))
                .toList();
    }
}
