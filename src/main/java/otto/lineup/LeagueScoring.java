package otto.lineup;

import java.util.Map;
import java.util.Optional;

/**
 * The league's own scoring settings applied to one stat line. Every
 * point total in the system prices through here - a projected week, a
 * played week, or the points a defense allowed - so a projection and a
 * result are never counted two different ways.
 *
 * Sleeper's and nflverse's pre-computed fantasy-point fields
 * (pts_ppr, fantasy_points_ppr and friends) are never read: no scoring
 * setting carries their key. The TE reception premium is the one
 * setting that keys off position instead of a stat, so it is applied
 * here rather than by the caller.
 */
public record LeagueScoring(Map<String, Double> settings) {

    private static final String TE_RECEPTION_BONUS = "bonus_rec_te";
    private static final String RECEPTIONS = "rec";
    private static final String TIGHT_END = "TE";

    public boolean isEmpty() {
        return settings.isEmpty();
    }

    /**
     * Empty when the stat line carries no stat the league scores - that
     * is "no projection available", never a silent zero.
     */
    public Optional<Double> price(Map<String, Double> statLine, String position) {
        if (statLine == null) {
            return Optional.empty();
        }
        boolean scored = false;
        double total = 0.0;
        for (Map.Entry<String, Double> stat : statLine.entrySet()) {
            Double weight = settings.get(stat.getKey());
            if (weight == null) {
                continue;
            }
            scored = true;
            total += weight * stat.getValue();
        }
        Double teBonus = settings.get(TE_RECEPTION_BONUS);
        Double receptions = statLine.get(RECEPTIONS);
        if (TIGHT_END.equals(position) && teBonus != null && receptions != null) {
            // The premium is a scoring setting like any other: a league
            // that pays it and nothing else still scores the catch.
            scored = true;
            total += teBonus * receptions;
        }
        return scored ? Optional.of(total) : Optional.empty();
    }
}
