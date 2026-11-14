package otto.nflverse;

import java.util.Map;
import java.util.Optional;

/**
 * The part of a defense a depth-chart place belongs to, by the job the
 * player does rather than by the letters on the chart.
 *
 * The letters alone do not say it. nflverse copies ESPN's charts, which
 * name a base 3-4 front's outside linebackers WLB and SLB and its
 * linemen LDE, NT and RDE, while a base 4-3 front uses the same WLB and
 * SLB for off-ball linebackers and LDE and RDE for its edge rushers. So
 * the front the chart is drawn in decides those four places.
 */
public enum DefensiveGroup {
    CORNERBACK,
    SAFETY,
    LINEBACKER,
    EDGE,
    INTERIOR_LINE;

    private static final Map<String, DefensiveGroup> ANY_FRONT = Map.ofEntries(
            Map.entry("LCB", CORNERBACK), Map.entry("RCB", CORNERBACK),
            Map.entry("NB", CORNERBACK),
            Map.entry("FS", SAFETY), Map.entry("SS", SAFETY),
            Map.entry("MLB", LINEBACKER), Map.entry("LILB", LINEBACKER),
            Map.entry("RILB", LINEBACKER),
            Map.entry("NT", INTERIOR_LINE), Map.entry("LDT", INTERIOR_LINE),
            Map.entry("RDT", INTERIOR_LINE));

    private static final Map<String, DefensiveGroup> THREE_FOUR_FRONT = Map.of(
            "WLB", EDGE, "SLB", EDGE, "LDE", INTERIOR_LINE, "RDE", INTERIOR_LINE);

    private static final Map<String, DefensiveGroup> FOUR_THREE_FRONT = Map.of(
            "WLB", LINEBACKER, "SLB", LINEBACKER, "LDE", EDGE, "RDE", EDGE);

    /**
     * Places one depth-chart row.
     *
     * @param formation the chart's {@code pos_grp}, such as "Base 3-4 D";
     *        an offensive or special-teams formation places nothing
     * @param position the chart's {@code pos_abb}, such as "LCB"
     * @return the group, or empty for a place that is not on a defense
     * @throws IllegalStateException when a defensive formation or place
     *         is one this code cannot read, which is schema drift
     */
    static Optional<DefensiveGroup> of(String formation, String position) {
        if (!formation.endsWith(" D")) {
            return Optional.empty();
        }
        Map<String, DefensiveGroup> front = formation.contains("3-4") ? THREE_FOUR_FRONT
                : formation.contains("4-3") ? FOUR_THREE_FRONT
                : Map.of();
        DefensiveGroup group = ANY_FRONT.getOrDefault(position, front.get(position));
        if (group == null) {
            throw new IllegalStateException("schema drift: cannot place %s in formation \"%s\""
                    .formatted(position, formation));
        }
        return Optional.of(group);
    }
}
