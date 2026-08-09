package otto.lineup;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import org.springframework.stereotype.Component;

/**
 * Finds the optimal legal lineup. Slot eligibility sets are nested
 * (QB inside SUPER_FLEX; RB, WR, TE inside FLEX inside SUPER_FLEX),
 * so filling slots from most to least restrictive with the best
 * remaining eligible player is optimal by the standard exchange
 * argument - no search needed.
 */
@Component
public class LineupOptimizer {

    /**
     * Assigns players to slots for the maximum total projected points.
     *
     * @param slots the league's starting slots, in lineup order
     * @param points projected points per candidate player
     * @param positions position per candidate player
     * @return slot index to player id; a slot with no eligible player left is absent
     */
    public Map<Integer, String> assign(List<Slot> slots, Map<String, Double> points,
            Map<String, String> positions) {
        List<Integer> slotOrder = IntStream.range(0, slots.size())
                .boxed()
                .sorted(Comparator.comparingInt(index -> slots.get(index).eligible().size()))
                .toList();

        Map<Integer, String> assignment = new HashMap<>();
        Set<String> used = new HashSet<>();
        for (int slotIndex : slotOrder) {
            Slot slot = slots.get(slotIndex);
            points.entrySet().stream()
                    .filter(candidate -> !used.contains(candidate.getKey()))
                    .filter(candidate -> slot.accepts(positions.get(candidate.getKey())))
                    .max(Map.Entry.comparingByValue())
                    .ifPresent(best -> {
                        assignment.put(slotIndex, best.getKey());
                        used.add(best.getKey());
                    });
        }
        return assignment;
    }

    /**
     * Reads a reshuffle as concrete "start A over B" pairs.
     *
     * Both sides sort strongest first, so a multi-player reshuffle
     * pairs the best entering player with the best leaving one.
     * Pairing best against worst would inflate one pair's gain and
     * describe a swap nobody would name that way.
     *
     * @param current the player ids in the lineup now
     * @param optimal the player ids the optimizer chose
     * @param points projected points per movable player; a player
     *        absent from it cannot be moved, so neither side of a pair
     *        is ever built from one. A caller may pin players outside
     *        this map into the optimal lineup - a locked starter keeps
     *        their slot - and pairing against them would price a swap
     *        the user cannot make.
     */
    public List<LineupSwap> swaps(Collection<String> current, Collection<String> optimal,
            Map<String, Double> points) {
        Set<String> currentIds = new HashSet<>(current);
        Set<String> optimalIds = new HashSet<>(optimal);
        Comparator<String> strongestFirst = Comparator
                .comparingDouble((String playerId) -> points.get(playerId))
                .reversed();
        List<String> entering = optimalIds.stream()
                .filter(points::containsKey)
                .filter(playerId -> !currentIds.contains(playerId))
                .sorted(strongestFirst)
                .toList();
        List<String> leaving = currentIds.stream()
                .filter(points::containsKey)
                .filter(playerId -> !optimalIds.contains(playerId))
                .sorted(strongestFirst)
                .toList();

        List<LineupSwap> swaps = new ArrayList<>();
        int pairs = Math.min(entering.size(), leaving.size());
        for (int pair = 0; pair < pairs; pair++) {
            String in = entering.get(pair);
            String out = leaving.get(pair);
            swaps.add(new LineupSwap(in, out, points.get(in) - points.get(out)));
        }
        return swaps;
    }
}
