package otto.lineup;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Projected points for one week: stat line times the league's exact
 * scoring settings, computed in Java. Sleeper's pre-computed points
 * fields (pts_ppr and friends) are never read - no scoring setting
 * carries their key. The TE reception premium is the one setting that
 * keys off position instead of a stat, so it is applied here.
 */
public class ProjectionTable {

    public static final String NO_PROJECTION = "no projection available";

    private static final String TE_RECEPTION_BONUS = "bonus_rec_te";

    private final Map<String, Double> scoring;
    private final Map<String, Map<String, Double>> statLines;

    public ProjectionTable(Map<String, Double> scoring, Map<String, Map<String, Double>> statLines) {
        this.scoring = scoring;
        this.statLines = statLines;
    }

    /**
     * Empty when the player has no stat line, or a stat line with no
     * stat the league scores - that is "no projection available", never
     * a silent zero.
     */
    public Optional<Double> points(String playerId, String position) {
        Map<String, Double> line = statLines.get(playerId);
        if (line == null) {
            return Optional.empty();
        }
        boolean scored = false;
        double total = 0.0;
        for (Map.Entry<String, Double> stat : line.entrySet()) {
            Double weight = scoring.get(stat.getKey());
            if (weight == null) {
                continue;
            }
            scored = true;
            total += weight * stat.getValue();
        }
        Double teBonus = scoring.get(TE_RECEPTION_BONUS);
        Double receptions = line.get("rec");
        if ("TE".equals(position) && teBonus != null && receptions != null) {
            total += teBonus * receptions;
        }
        return scored ? Optional.of(total) : Optional.empty();
    }

    public String display(String playerId, String position) {
        return points(playerId, position)
                .map(points -> String.format(Locale.ROOT, "%.1f", points))
                .orElse(NO_PROJECTION);
    }
}
