package otto.lineup;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One starting lineup slot with the positions it accepts. Built from
 * the league's roster_positions; bench-type entries (BN, IR, TAXI) are
 * not slots. An unrecognized starting slot type accepts every position,
 * so a drifted new slot name degrades to lenient instead of flagging
 * legal lineups as illegal.
 *
 * Only nested eligibility sets are modeled (each set contains or is
 * contained by every overlapping set): the optimizer's greedy fill is
 * provably optimal exactly then. Partial-overlap slot types such as
 * WRRB_FLEX deliberately fall to the lenient all-position set rather
 * than break that guarantee.
 */
public record Slot(String name, Set<String> eligible) {

    private static final Set<String> BENCH_TYPES = Set.of("BN", "IR", "TAXI");
    private static final Set<String> ALL_POSITIONS = Set.of("QB", "RB", "WR", "TE");
    private static final Map<String, Set<String>> ELIGIBILITY = Map.of(
            "QB", Set.of("QB"),
            "RB", Set.of("RB"),
            "WR", Set.of("WR"),
            "TE", Set.of("TE"),
            "FLEX", Set.of("RB", "WR", "TE"),
            "SUPER_FLEX", ALL_POSITIONS);

    /**
     * The starting slots of a league, in the order Sleeper aligns the
     * starters array to.
     */
    public static List<Slot> startingSlots(List<String> rosterPositions) {
        return rosterPositions.stream()
                .filter(position -> !BENCH_TYPES.contains(position))
                .map(position -> new Slot(position,
                        ELIGIBILITY.getOrDefault(position, ALL_POSITIONS)))
                .toList();
    }

    public boolean accepts(String position) {
        return position != null && eligible.contains(position);
    }
}
