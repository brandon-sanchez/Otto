package otto.waivers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a caller asked the waiver board for: which positions, how many
 * candidates, who the user would drop for them, and whether to keep to
 * the positions he is short at. The Tuesday Alert asks for the top five
 * at every position; a chat question asks for whatever the user said.
 *
 * <p>None of it changes a score. The positions, the count, the drop
 * side and the needs filter all narrow what the board <em>shows</em>;
 * the replacement levels, the roster-need read and the 50-point scale
 * are computed over every position whatever was asked.
 *
 * @param replacing the rostered players the user would drop, by name or
 *        Sleeper id, in the order he named them; empty when he named
 *        nobody
 * @param needsOnly keep the board to the positions where the user has
 *        no bench answer above replacement level
 * @param note what this query could not honour, said plainly, or null
 *        when it was taken exactly as asked
 */
public record WaiverQuery(Set<String> positions, int count, List<String> replacing,
        boolean needsOnly, String note) {

    public WaiverQuery {
        // Board order, and nobody's to change after the fact.
        positions = Collections.unmodifiableSet(new LinkedHashSet<>(positions));
        // A blank entry is a name the caller did not really give, and
        // refusing it by name would mean refusing "".
        replacing = replacing == null ? List.of() : replacing.stream()
                .filter(reference -> reference != null && !reference.isBlank())
                .map(String::trim)
                .toList();
    }

    /** The positions the Player Directory keeps, in board order. */
    public static final List<String> ALL_POSITIONS = List.of("QB", "RB", "WR", "TE");

    /** The Tuesday Alert's board: the top five, every position. */
    public static final int TUESDAY_COUNT = 5;

    /**
     * As many as one chat message can carry without becoming a
     * spreadsheet. A longer request is answered up to here and told so
     * - a board the user cannot read is not an answer.
     */
    private static final int MOST_CANDIDATES = 50;

    /**
     * As many drops as one board can price and stay readable. Every
     * named player adds a line to every candidate, so the answer grows
     * by the product of the two - and nobody drops six players for one
     * pickup.
     */
    private static final int MOST_DROPS = 5;

    private static final Set<String> FLEX = Set.of("RB", "WR", "TE");

    /** One position group a user can ask for, or the reason he cannot. */
    public sealed interface Parsed {

        record Ok(WaiverQuery query) implements Parsed {
        }

        record Rejected(String reason) implements Parsed {
        }
    }

    public static WaiverQuery everyPosition(int count) {
        return new WaiverQuery(new LinkedHashSet<>(ALL_POSITIONS), count, List.of(), false, null);
    }

    /**
     * Reads what the user asked for. An unknown position is refused by
     * name rather than quietly widened to every position: answering a
     * question the user did not ask is worse than saying so.
     *
     * @param position a position, a flex group, "all", or nothing
     * @param count how many candidates, or nothing for five
     * @param replacing the players the user would drop, or nothing
     * @param needsOnly keep to the positions he is short at
     */
    public static Parsed of(String position, Integer count, List<String> replacing,
            Boolean needsOnly) {
        int asked = count == null || count < 1 ? TUESDAY_COUNT : count;
        int wanted = Math.min(asked, MOST_CANDIDATES);
        boolean needs = Boolean.TRUE.equals(needsOnly);
        List<String> named = replacing == null ? List.of() : replacing;
        List<String> drops = named.size() <= MOST_DROPS
                ? named
                : List.copyOf(named.subList(0, MOST_DROPS));
        List<String> notes = new ArrayList<>();
        if (asked > wanted) {
            notes.add(("you asked for %d targets and I ranked the top %d: past that a reply stops "
                    + "being a message and starts being a spreadsheet").formatted(asked, wanted));
        }
        if (named.size() > drops.size()) {
            notes.add(("you named %d players to drop and I priced the first %d: every name adds a "
                    + "line to every candidate").formatted(named.size(), drops.size()));
        }
        String note = notes.isEmpty() ? null : String.join("; ", notes);
        if (position == null || position.isBlank()) {
            return new Parsed.Ok(new WaiverQuery(new LinkedHashSet<>(ALL_POSITIONS), wanted,
                    drops, needs, note));
        }
        String normalized = position.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        Set<String> positions = switch (normalized) {
            case "ALL", "ANY", "SUPER_FLEX", "SUPERFLEX" -> new LinkedHashSet<>(ALL_POSITIONS);
            case "FLEX" -> ordered(FLEX);
            case "QB", "RB", "WR", "TE" -> Set.of(normalized);
            default -> Set.of();
        };
        if (positions.isEmpty()) {
            return new Parsed.Rejected(
                    ("I rank waiver targets at QB, RB, WR and TE, or at FLEX and all; "
                            + "\"%s\" is none of those").formatted(position));
        }
        return new Parsed.Ok(new WaiverQuery(positions, wanted, drops, needs, note));
    }

    /** Keeps board order, so two identical questions read the same way. */
    private static Set<String> ordered(Set<String> positions) {
        return ALL_POSITIONS.stream()
                .filter(positions::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public boolean covers(String position) {
        return position != null && positions.contains(position);
    }
}
