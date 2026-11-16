package otto.trade;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import otto.advice.Ordinal;
import otto.sleeper.SleeperAdapter;

/**
 * How many players of each position a league starts, read from its
 * {@code roster_positions}, and the two trade factors that follow from
 * it: the rank Replacement Level is read at and the scarcity multiplier.
 *
 * <p>A position's replacement rank is one player for every dedicated
 * starting slot in the league, so a 12-team league with two RB slots
 * prices a back above the 24th RB. A Superflex slot counts as a
 * quarterback slot, because managers fill it with a quarterback whenever
 * they have one. A plain flex slot counts toward no position: it takes
 * whichever back, receiver or tight end is left over, so it adds demand
 * at none of them in particular.
 *
 * @param teams how many teams the league holds
 * @param slots dedicated starting slots a team has at each position,
 *        Superflex counted at QB
 */
public record LeagueShape(int teams, Map<String, Integer> slots) {

    private static final double NO_SCARCITY = 1.00;

    /**
     * How much harder a position is to refill than a receiver, who is
     * the yardstick because the waiver wire always holds a startable one.
     * Tight ends run dry below their first dozen, and backs a little
     * earlier than receivers. The quarterback's multiplier depends on
     * the league and is set in {@link #scarcity(String)}.
     */
    private static final Map<String, Double> SCARCITY = Map.of(
            "TE", 1.10,
            "RB", 1.05,
            "WR", 1.00);

    /**
     * A league that starts two quarterbacks turns every quarterback lost
     * into a starting slot that can't be refilled.
     */
    private static final double TWO_QUARTERBACK_SCARCITY = 1.20;

    private static final List<String> POSITIONS = List.of("QB", "RB", "WR", "TE");

    public LeagueShape {
        slots = Map.copyOf(slots);
    }

    /**
     * Reads the league's shape.
     *
     * @param rosterCount how many teams the Snapshot holds, used when the
     *        league document doesn't say {@code num_teams}
     */
    public static LeagueShape of(SleeperAdapter.League league, int rosterCount) {
        Map<String, Integer> slots = new LinkedHashMap<>();
        for (String slot : league.rosterPositions()) {
            String position = slot.toUpperCase(Locale.ROOT);
            if (position.equals("SUPER_FLEX")) {
                position = "QB";
            }
            if (POSITIONS.contains(position)) {
                slots.merge(position, 1, Integer::sum);
            }
        }
        return new LeagueShape(league.rules().teams().orElse(rosterCount), slots);
    }

    /** True when a team can start two quarterbacks, through Superflex or two QB slots. */
    public boolean twoQuarterbacks() {
        return slots.getOrDefault("QB", 0) >= 2;
    }

    /**
     * Returns the rank Replacement Level is read at: one player for every
     * dedicated starting slot in the league, and at least the first one.
     */
    public int replacementRank(String position) {
        return Math.max(1, teams * slots.getOrDefault(position, 0));
    }

    /** Returns the position's scarcity multiplier in this league. */
    public double scarcity(String position) {
        if (position.equals("QB")) {
            return twoQuarterbacks() ? TWO_QUARTERBACK_SCARCITY : NO_SCARCITY;
        }
        return SCARCITY.getOrDefault(position, NO_SCARCITY);
    }

    /**
     * Says where each position's free agent line sits and why, in a
     * sentence the user can read.
     */
    public String describe() {
        String lines = POSITIONS.stream()
                .filter(slots::containsKey)
                .map(position -> "%s among %ss".formatted(Ordinal.of(replacementRank(position)),
                        position))
                .collect(Collectors.collectingAndThen(Collectors.toList(), LeagueShape::listed));
        String quarterbacks = twoQuarterbacks()
                ? "Your league can start two quarterbacks, so a quarterback is the hardest "
                        + "player to replace and carries the biggest premium."
                : "Your league starts only one quarterback a team, so quarterbacks are easy to "
                        + "replace and carry no Superflex premium.";
        return ("A player is worth his rest-of-season points above the free agent who ranks %s "
                + "this week, one for every starting slot your %d teams fill. %s")
                .formatted(lines, teams, quarterbacks);
    }

    private static String listed(List<String> items) {
        if (items.size() <= 1) {
            return String.join("", items);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.getLast();
    }
}
