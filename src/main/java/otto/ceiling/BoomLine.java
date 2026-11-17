package otto.ceiling;

import java.util.Map;
import java.util.Optional;

import otto.lineup.LeagueScoring;

/**
 * The score a week must reach to count as a boom week at one position:
 * a top weekly finish, QB6, RB12, WR12 or TE6.
 *
 * <p>Each line is the stat line of a typical week at that rank, priced in
 * the league's own scoring, so it moves with the league's reception
 * points, TE premium and touchdown values. The stat lines are the average
 * line of the week ranked exactly QB6, RB12, WR12 or TE6 in the 2019-2025
 * ffopportunity weekly data, scaled to price at the research note's
 * median full-PPR cutoffs of 23.5, 17.4, 19.5 and 14.6 points. Scoring
 * the line does not carry, such as yardage or first-down bonuses, leaves
 * the line where full PPR would put it.
 *
 * @param rank the finish the line stands for, such as {@code WR12}
 * @param points the line in league scoring
 */
public record BoomLine(String rank, double points) {

    private record TypicalWeek(String rank, Map<String, Double> stats) {
    }

    private static final Map<String, TypicalWeek> TYPICAL_WEEKS = Map.of(
            "QB", new TypicalWeek("QB6", Map.of(
                    "pass_yd", 269.4, "pass_td", 2.38, "pass_int", 0.53, "pass_2pt", 0.15,
                    "rush_yd", 22.2, "rush_td", 0.31, "fum_lost", 0.05)),
            "RB", new TypicalWeek("RB12", Map.of(
                    "rush_yd", 68.1, "rush_td", 0.75, "rush_2pt", 0.03, "rec", 3.19,
                    "rec_yd", 24.3, "rec_td", 0.09, "fum_lost", 0.06)),
            "WR", new TypicalWeek("WR12", Map.of(
                    "rush_yd", 2.6, "rush_td", 0.03, "rec", 6.2, "rec_yd", 86.4,
                    "rec_td", 0.72, "fum_lost", 0.03)),
            "TE", new TypicalWeek("TE6", Map.of(
                    "rush_yd", 0.5, "rec", 5.27, "rec_yd", 60.4, "rec_td", 0.54,
                    "rec_2pt", 0.02, "fum_lost", 0.02)));

    /**
     * Returns the boom line for a position in the league's scoring, or
     * empty for a position without one or a league that scores none of
     * the typical week's stats.
     */
    public static Optional<BoomLine> of(String position, LeagueScoring scoring) {
        TypicalWeek week = TYPICAL_WEEKS.get(position);
        if (week == null) {
            return Optional.empty();
        }
        return scoring.price(week.stats(), position).map(points -> new BoomLine(week.rank(), points));
    }
}
