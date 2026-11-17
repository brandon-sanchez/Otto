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

    /**
     * True when at least one player has a stat line the league scores.
     * Sleeper answers a week it has not projected yet with an entry for
     * every player that carries only draft-position fields, so a week
     * with stat lines can still be unprojected.
     */
    public boolean projectsAnyone() {
        return statLines.values().stream()
                .anyMatch(statLine -> scoring.price(statLine, null).isPresent());
    }

    public Optional<Double> points(String playerId, String position) {
        return scoring.price(statLines.get(playerId), position);
    }

    /**
     * Returns the player's position: the known one, or kicker or defense
     * as {@link PlayerPositions} reads them from this week's stat line;
     * empty when neither says.
     *
     * @param known the position the roster or directory gives, or null
     */
    public Optional<String> positionOf(String playerId, String known) {
        return PlayerPositions.of(playerId, known, statLines.get(playerId));
    }

    public String display(String playerId, String position) {
        return points(playerId, position)
                .map(points -> String.format(Locale.ROOT, "%.1f", points))
                .orElse(NO_PROJECTION);
    }
}
