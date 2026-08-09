package otto.lineup;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Projected points for one week: each player's projected stat line
 * priced through {@link LeagueScoring}. A player with no stat line, or
 * a stat line carrying no stat the league scores, reads "no projection
 * available" rather than zero.
 */
public class ProjectionTable {

    public static final String NO_PROJECTION = "no projection available";

    private final LeagueScoring scoring;
    private final Map<String, Map<String, Double>> statLines;

    public ProjectionTable(LeagueScoring scoring, Map<String, Map<String, Double>> statLines) {
        this.scoring = scoring;
        this.statLines = statLines;
    }

    public Optional<Double> points(String playerId, String position) {
        return scoring.price(statLines.get(playerId), position);
    }

    public String display(String playerId, String position) {
        return points(playerId, position)
                .map(points -> String.format(Locale.ROOT, "%.1f", points))
                .orElse(NO_PROJECTION);
    }
}
