package otto.nflverse;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The part of a defense a depth-chart place belongs to, by the job the
 * player does rather than by the letters on the chart.
 *
 * The letters alone do not say it. nflverse copies ESPN's charts, which
 * name a three-man front's outside linebackers WLB and SLB and its
 * linemen LDE, NT and RDE, while a four-man front uses the same WLB and
 * SLB for off-ball linebackers and LDE and RDE for its edge rushers. So
 * the front the chart is drawn in decides those four places, and the
 * front is the first number of the formation: "Base 3-4 D" and
 * "Base 3-3-5 D" are three-man fronts, "Base 4-3 D" and "Base 4-2-5 D"
 * four-man fronts.
 */
public enum DefensiveGroup {
    CORNERBACK,
    SAFETY,
    LINEBACKER,
    EDGE,
    INTERIOR_LINE,

    /**
     * A defensive place whose job neither its letters nor its formation
     * says, such as WLB in a formation with no front number. The player
     * is kept as a starter rather than dropped or guessed at, and an
     * unfamiliar place or formation from upstream never fails the feed.
     */
    UNPLACED;

    private static final Pattern FRONT = Pattern.compile("\\b([34])-\\d");

    private static final Map<String, DefensiveGroup> ANY_FRONT = Map.ofEntries(
            Map.entry("LCB", CORNERBACK), Map.entry("RCB", CORNERBACK),
            Map.entry("NB", CORNERBACK), Map.entry("DB", CORNERBACK),
            Map.entry("FS", SAFETY), Map.entry("SS", SAFETY),
            Map.entry("MLB", LINEBACKER), Map.entry("LILB", LINEBACKER),
            Map.entry("RILB", LINEBACKER),
            Map.entry("NT", INTERIOR_LINE), Map.entry("LDT", INTERIOR_LINE),
            Map.entry("RDT", INTERIOR_LINE));

    private static final Map<String, DefensiveGroup> THREE_MAN_FRONT = Map.of(
            "WLB", EDGE, "SLB", EDGE, "LDE", INTERIOR_LINE, "RDE", INTERIOR_LINE);

    private static final Map<String, DefensiveGroup> FOUR_MAN_FRONT = Map.of(
            "WLB", LINEBACKER, "SLB", LINEBACKER, "LDE", EDGE, "RDE", EDGE);

    /**
     * Places one depth-chart row.
     *
     * @param formation the chart's {@code pos_grp}, such as "Base 3-4 D";
     *        an offensive or special-teams formation places nothing
     * @param position the chart's {@code pos_abb}, such as "LCB"
     * @return the group, {@link #UNPLACED} for a defensive place this code
     *         cannot read, or empty for a place that is not on a defense
     */
    static Optional<DefensiveGroup> of(String formation, String position) {
        if (!formation.endsWith(" D")) {
            return Optional.empty();
        }
        Matcher front = FRONT.matcher(formation);
        Map<String, DefensiveGroup> byFront = !front.find() ? Map.of()
                : front.group(1).equals("3") ? THREE_MAN_FRONT
                : FOUR_MAN_FRONT;
        return Optional.of(ANY_FRONT.getOrDefault(position,
                byFront.getOrDefault(position, UNPLACED)));
    }
}
