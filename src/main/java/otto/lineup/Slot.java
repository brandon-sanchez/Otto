package otto.lineup;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One starting lineup slot with the positions it accepts. Built from
 * the league's roster_positions; the bench (BN) is not a slot. A K slot
 * takes only a kicker and a DEF slot only a team defense, as
 * {@link PlayerPositions} reads them. An unrecognized starting slot type
 * accepts every skill position, so a drifted new slot name degrades to
 * lenient instead of flagging legal lineups as illegal.
 *
 * Only nested eligibility sets are modeled (each set contains or is
 * contained by every overlapping set): the optimizer's greedy fill is
 * provably optimal exactly then. Partial-overlap slot types such as
 * WRRB_FLEX deliberately fall to the lenient all-position set rather
 * than break that guarantee.
 */
public record Slot(String name, Set<String> eligible) {

    private static final Set<String> ALL_POSITIONS = Set.of("QB", "RB", "WR", "TE");
    private static final Map<String, Set<String>> ELIGIBILITY = Map.of(
            "QB", Set.of("QB"),
            "RB", Set.of("RB"),
            "WR", Set.of("WR"),
            "TE", Set.of("TE"),
            "FLEX", Set.of("RB", "WR", "TE"),
            "SUPER_FLEX", ALL_POSITIONS,
            PlayerPositions.KICKER, Set.of(PlayerPositions.KICKER),
            PlayerPositions.DEFENSE, Set.of(PlayerPositions.DEFENSE));

    /** The plain names of the slots whose Sleeper code does not spell them out. */
    private static final Map<String, String> LABELS = Map.of(
            "SUPER_FLEX", "Superflex",
            "REC_FLEX", "WR/TE Flex",
            "WRRB_FLEX", "WR/RB Flex");

    /**
     * The starting slots of a league, in the order Sleeper aligns the
     * starters array to.
     */
    public static List<Slot> startingSlots(List<String> rosterPositions) {
        return rosterPositions.stream()
                .filter(position -> !"BN".equals(position))
                .map(position -> new Slot(position,
                        ELIGIBILITY.getOrDefault(position, ALL_POSITIONS)))
                .toList();
    }

    /**
     * Returns the slot's name as the user reads it: "Superflex" for
     * SUPER_FLEX, "Flex" for FLEX, "WR/TE Flex" for REC_FLEX. A position
     * code such as QB or DEF stays as it is, and any other name with an
     * underscore is written as words, such as "IDP Flex".
     */
    public String label() {
        return label(name);
    }

    /**
     * Returns a Sleeper roster position as the user reads it; see
     * {@link #label()}.
     */
    public static String label(String name) {
        String known = LABELS.get(name);
        if (known != null) {
            return known;
        }
        return Arrays.stream(name.split("_"))
                .map(word -> word.equals("FLEX") ? "Flex" : word)
                .collect(Collectors.joining(" "));
    }

    /**
     * Returns whether this slot's eligibility is its own rather than the
     * lenient fallback, so a slot type Otto does not know, such as an
     * IDP slot, is never filled with a skill player in its place.
     */
    public boolean modelledExactly() {
        return ELIGIBILITY.containsKey(name);
    }

    /**
     * Returns whether a player at the position may start here.
     *
     * @param position the player's position as {@link PlayerPositions}
     *        reads it; null for a player whose position is unknown, whom
     *        no slot accepts
     */
    public boolean accepts(String position) {
        return position != null && eligible.contains(position);
    }
}
