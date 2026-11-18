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
 * setting carries their key. The reception premiums for a tight end,
 * running back or receiver are the settings that key off position
 * instead of a stat, so they are applied here rather than by the caller.
 */
public record LeagueScoring(Map<String, Double> settings) {

    private static final String RECEPTIONS = "rec";

    private static final Map<String, String> RECEPTION_PREMIUMS = Map.of(
            "TE", "bonus_rec_te",
            "RB", "bonus_rec_rb",
            "WR", "bonus_rec_wr");


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
        String premiumKey = position == null ? null : RECEPTION_PREMIUMS.get(position);
        Double premium = premiumKey == null ? null : settings.get(premiumKey);
        Double receptions = statLine.get(RECEPTIONS);
        // Sleeper's lines may already carry the premium as a stat, which
        // the loop above priced.
        if (premium != null && receptions != null && !statLine.containsKey(premiumKey)) {
            // The premium is a scoring setting like any other: a league
            // that pays it and nothing else still scores the catch.
            scored = true;
            total += premium * receptions;
        }
        return scored ? Optional.of(total) : Optional.empty();
    }
}
