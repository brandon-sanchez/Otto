package otto.league;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import otto.advice.Ordinal;
import otto.advice.Prose;
import otto.lineup.PositionCutoffs;
import otto.lineup.PositionRanking;
import otto.sleeper.SleeperAdapter;

/**
 * How many players of each position a league starts, and the two trade
 * factors that follow from it: the rank Replacement Level is read at and
 * the scarcity multiplier.
 *
 * <p>A position's replacement rank is the number of players at it who
 * start across the league when every team starts its best lineup, flex
 * and Superflex spots included, as {@code LeagueStarters} counts them.
 * A twelve-team league with two RB slots and a flex whose teams fill
 * most flex spots with backs prices a back above the 30th RB or so, not
 * the 24th. When no projections are read yet to fill those lineups, the
 * rank falls back to one player for every dedicated slot, with a
 * Superflex slot counted as a quarterback slot because managers fill it
 * with one whenever they can. A position neither gives a rank, such as a
 * tight end in a league with no tight end slot, reads its rank from the
 * configured {@code otto.replacement-cutoffs}.
 *
 * @param teams how many teams the league holds
 * @param slots dedicated starting slots a team has at each position,
 *        Superflex counted at QB
 * @param starters how many players at each position start across the
 *        league in every team's best lineup; empty when no lineup could
 *        be filled
 * @param fallback the rank for a position the slots and starters give
 *        none; 0 for a position it doesn't name, such as a kicker
 */
public record LeagueShape(int teams, Map<String, Integer> slots, Map<String, Integer> starters,
        PositionCutoffs fallback) {

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
        starters = Map.copyOf(starters);
    }

    /**
     * Reads the league's shape from its slots alone, with no lineup filled.
     *
     * @param rosterCount how many teams the Snapshot holds, used when the
     *        league document doesn't say {@code num_teams}
     * @param fallback the configured {@code otto.replacement-cutoffs}
     */
    public static LeagueShape of(SleeperAdapter.League league, int rosterCount,
            PositionCutoffs fallback) {
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
        return new LeagueShape(league.rules().teams().orElse(rosterCount), slots, Map.of(),
                fallback);
    }

    /**
     * Returns this shape with the starters counted from every team's best
     * lineup, which then set each replacement rank.
     *
     * @param starters players starting at each position across the
     *        league; only QB, RB, WR and TE are kept
     */
    public LeagueShape withStarters(Map<String, Integer> starters) {
        Map<String, Integer> kept = new LinkedHashMap<>();
        starters.forEach((position, count) -> {
            if (POSITIONS.contains(position)) {
                kept.put(position, count);
            }
        });
        return new LeagueShape(teams, slots, kept, fallback);
    }

    /** True when a team can start two quarterbacks, through Superflex or two QB slots. */
    public boolean twoQuarterbacks() {
        return slots.getOrDefault("QB", 0) >= 2;
    }

    /**
     * Returns the rank Replacement Level is read at: the players at the
     * position who start in the league's best lineups, or one for every
     * dedicated slot when no lineup was filled, and at least the first one.
     * A position the league's lineup gives no rank reads the fallback
     * cutoff, which is 0, no rank at all, for a position it doesn't name.
     */
    public int replacementRank(String position) {
        if (!ranks(position)) {
            return fallback.forPosition(position);
        }
        return Math.max(1, starters.isEmpty()
                ? teams * slots.getOrDefault(position, 0)
                : starters.getOrDefault(position, 0));
    }

    /** True when the position has a replacement rank at all. */
    public boolean hasReplacementLevel(String position) {
        return replacementRank(position) > 0;
    }

    /**
     * Returns what a week of Replacement Level is worth at the position:
     * the projection of the player at its replacement rank. Empty when the
     * table does not reach that far down, which is a shallow table rather
     * than a replacement level of zero, or when the position has no rank.
     */
    public Optional<Double> replacementLevel(PositionRanking ranking, String position) {
        return ranking.pointsAtRank(position, replacementRank(position));
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
                .filter(this::ranks)
                .map(position -> "%s among %ss".formatted(Ordinal.of(replacementRank(position)),
                        position))
                .collect(Collectors.collectingAndThen(Collectors.toList(), Prose::listed));
        String quarterbacks = twoQuarterbacks()
                ? "Your league can start two quarterbacks, so a quarterback is the hardest "
                        + "player to replace and carries the biggest premium."
                : "Your league starts only one quarterback a team, so quarterbacks are easy to "
                        + "replace and carry no Superflex premium.";
        String basis = starters.isEmpty()
                ? "one for every starting slot your %d teams fill".formatted(teams)
                : "one for every player your %d teams start there in their best lineups, flex "
                        .formatted(teams) + "spots included";
        return ("A player is worth his rest-of-season points above the free agent who ranks %s "
                + "this week, %s. %s").formatted(lines, basis, quarterbacks);
    }

    /** True when the league's lineup gives the position a replacement rank of its own. */
    private boolean ranks(String position) {
        return position != null
                && (starters.containsKey(position) || slots.containsKey(position));
    }
}
