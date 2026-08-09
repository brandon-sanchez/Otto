package otto.waivers;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a caller asked the waiver board for: which positions, and how
 * many candidates. The Tuesday Alert asks for the top five at every
 * position; a chat question asks for whatever the user said.
 *
 * @param note what this query could not honour, said plainly, or null
 *        when it was taken exactly as asked
 */
public record WaiverQuery(Set<String> positions, int count, String note) {

    public WaiverQuery {
        // Board order, and nobody's to change after the fact.
        positions = Collections.unmodifiableSet(new LinkedHashSet<>(positions));
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

    private static final Set<String> FLEX = Set.of("RB", "WR", "TE");

    /** One position group a user can ask for, or the reason he cannot. */
    public sealed interface Parsed {

        record Ok(WaiverQuery query) implements Parsed {
        }

        record Rejected(String reason) implements Parsed {
        }
    }

    public static WaiverQuery everyPosition(int count) {
        return new WaiverQuery(new LinkedHashSet<>(ALL_POSITIONS), count, null);
    }

    /**
     * Reads what the user asked for. An unknown position is refused by
     * name rather than quietly widened to every position: answering a
     * question the user did not ask is worse than saying so.
     *
     * @param position a position, a flex group, "all", or nothing
     * @param count how many candidates, or nothing for five
     */
    public static Parsed of(String position, Integer count) {
        int asked = count == null || count < 1 ? TUESDAY_COUNT : count;
        int wanted = Math.min(asked, MOST_CANDIDATES);
        String note = asked <= wanted ? null
                : ("you asked for %d targets and I ranked the top %d: past that a reply stops "
                        + "being a message and starts being a spreadsheet")
                                .formatted(asked, wanted);
        if (position == null || position.isBlank()) {
            return new Parsed.Ok(new WaiverQuery(new LinkedHashSet<>(ALL_POSITIONS), wanted, note));
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
        return new Parsed.Ok(new WaiverQuery(positions, wanted, note));
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
