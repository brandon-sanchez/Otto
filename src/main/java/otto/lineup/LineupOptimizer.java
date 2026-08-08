package otto.lineup;

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
}
