package otto.habits;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import otto.lineup.Slot;
import otto.sleeper.SleeperAdapter.StarterLine;

/**
 * The user's lineup in one scored week, and what it and his bench scored.
 *
 * @param slots the league's starting slots, in lineup order
 * @param starters the player id in each slot, aligned with {@code slots};
 *        an empty slot is {@link StarterLine#EMPTY_SLOT}
 * @param points what every player on the user's roster scored that week,
 *        bench included, in league scoring
 * @param leaguePoints what every rostered player in the league scored
 *        that week, on whichever roster held him
 * @param positions the position of each of the user's players, where Otto
 *        knew it
 * @param teams each of the user's players' NFL team, where Otto knew it
 * @param optimal the best legal lineup in hindsight from those points,
 *        aligned with {@code slots}; a slot the optimizer does not model,
 *        such as K or DEF, keeps the player the user started there
 * @param byes the NFL teams of the user's players that had no game that
 *        week; empty when Otto could not read the week's games
 * @param freeAgents the free agent each K or DEF slot could have held:
 *        the one projected highest that week among players on no
 *        roster, by position; empty for a position the league does not
 *        start
 * @param elsewhere what players the user had let go earlier in the
 *        season scored that week while on no roster, in league scoring;
 *        a player Sleeper has no stats for that week is absent
 */
public record PlayedWeek(List<String> slots, List<String> starters, Map<String, Double> points,
        Map<String, Double> leaguePoints, Map<String, String> positions,
        Map<String, String> teams, List<String> optimal, Set<String> byes,
        Map<String, FreeAgent> freeAgents, Map<String, Double> elsewhere) {

    /** The positions managers stream week to week, whose free agents the capture reads. */
    static final Set<String> STREAMED = Set.of("K", "DEF");

    /**
     * The free agent projected highest at a position one week, and what
     * he then scored.
     *
     * @param projected his projected points that week, in league scoring
     * @param scored what he scored, in league scoring; 0 when Sleeper had
     *        no stats for him
     */
    public record FreeAgent(String playerId, double projected, double scored) {
    }

    public PlayedWeek {
        slots = List.copyOf(slots);
        starters = List.copyOf(starters);
        points = Map.copyOf(points);
        leaguePoints = Map.copyOf(leaguePoints);
        positions = Map.copyOf(positions);
        teams = Map.copyOf(teams);
        optimal = List.copyOf(optimal);
        byes = byes == null ? Set.of() : Set.copyOf(byes);
        freeAgents = freeAgents == null ? Map.of() : Map.copyOf(freeAgents);
        elsewhere = elsewhere == null ? Map.of() : Map.copyOf(elsewhere);
    }

    /** Returns what the lineup the user set scored. */
    public double actual() {
        return total(starters);
    }

    /** Returns what the best lineup in hindsight would have scored. */
    public double best() {
        return total(optimal);
    }

    /** Returns whether the user started the player this week. */
    public boolean started(String playerId) {
        return starters.contains(playerId);
    }

    /** Returns whether the player's NFL team had no game that week. */
    public boolean onBye(String playerId) {
        String team = teams.get(playerId);
        return team != null && byes.contains(team);
    }

    /**
     * Returns what the player scored that week wherever he was: on the
     * user's roster, on another roster, or on no roster after the user let
     * him go; empty when Otto has no score for him.
     */
    public Optional<Double> scoredAnywhere(String playerId) {
        return Optional.ofNullable(points.get(playerId))
                .or(() -> Optional.ofNullable(leaguePoints.get(playerId)))
                .or(() -> Optional.ofNullable(elsewhere.get(playerId)));
    }

    /** Returns what the player scored for the user, or empty when he was not on the roster. */
    public Optional<Double> pointsOf(String playerId) {
        return Optional.ofNullable(points.get(playerId));
    }

    /**
     * Returns the most any benched player of the user's who could fill
     * the slot scored, or empty when no benched player could.
     */
    public Optional<Double> bestBenchFor(int slotIndex) {
        Slot slot = Slot.startingSlots(slots).get(slotIndex);
        return points.entrySet().stream()
                .filter(entry -> !starters.contains(entry.getKey()))
                .filter(entry -> fits(slot, positions.get(entry.getKey())))
                .map(Map.Entry::getValue)
                .max(Double::compare);
    }

    private static boolean fits(Slot slot, String position) {
        return slot.modelledExactly() ? slot.accepts(position) : slot.name().equals(position);
    }

    private double total(List<String> lineup) {
        return lineup.stream().mapToDouble(playerId -> points.getOrDefault(playerId, 0.0)).sum();
    }
}
