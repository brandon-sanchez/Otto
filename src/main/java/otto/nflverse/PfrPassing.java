package otto.nflverse;

import java.time.Instant;
import java.util.List;

/**
 * The stored PFR weekly advanced passing file: one row per passer per
 * regular-season game, trimmed to how often he was pressured.
 *
 * @param season the season these rows cover
 * @param priorSeasonFinal true when the current season has no played
 *        week yet, so these rows are last season's final record
 * @param assetUpdatedAt the release timestamp this copy was taken at
 * @param checkedAt when the hourly timestamp check last ran
 * @param coverage one unit per team per week
 */
public record PfrPassing(
        String season,
        boolean priorSeasonFinal,
        Instant assetUpdatedAt,
        Instant checkedAt,
        Coverage coverage,
        List<PasserLine> rows) implements NflverseFeed {

    /**
     * One passer's game, team codes in Sleeper's vocabulary.
     *
     * @param timesPressured how many of his dropbacks PFR charted as a
     *        pressure: a hurry, a hit or a sack
     */
    public record PasserLine(String gameId, int week, String team, String opponent,
            String pfrId, String player, int timesPressured) {
    }
}
