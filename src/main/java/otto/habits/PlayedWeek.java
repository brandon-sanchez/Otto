package otto.habits;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 */
public record PlayedWeek(List<String> slots, List<String> starters, Map<String, Double> points,
        Map<String, Double> leaguePoints, Map<String, String> positions,
        Map<String, String> teams, List<String> optimal) {

    public PlayedWeek {
        slots = List.copyOf(slots);
        starters = List.copyOf(starters);
        points = Map.copyOf(points);
        leaguePoints = Map.copyOf(leaguePoints);
        positions = Map.copyOf(positions);
        teams = Map.copyOf(teams);
        optimal = List.copyOf(optimal);
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
                .filter(entry -> slot.accepts(positions.get(entry.getKey())))
                .map(Map.Entry::getValue)
                .max(Double::compare);
    }

    private double total(List<String> lineup) {
        return lineup.stream().mapToDouble(playerId -> points.getOrDefault(playerId, 0.0)).sum();
    }
}
