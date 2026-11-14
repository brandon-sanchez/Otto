package otto.nflverse;

import java.time.Instant;
import java.util.List;

/**
 * The stored, trimmed PFR weekly rushing advanced stats
 * ({@code advstats_week_rush}): one row per ball carrier per
 * regular-season game, whatever his position, since a quarterback's
 * carries count against a defense as much as a back's do.
 *
 * @param season the season these rows cover
 * @param priorSeasonFinal true when the current season has no played
 *        week yet, so these rows are last season's final record
 * @param assetUpdatedAt the release timestamp this copy was taken at
 * @param checkedAt when the hourly timestamp check last ran
 * @param coverage one unit per game the rows hold, and when each last changed
 */
public record RushingAdvancedStats(
        String season,
        boolean priorSeasonFinal,
        Instant assetUpdatedAt,
        Instant checkedAt,
        Coverage coverage,
        List<RushLine> rows) implements NflverseFeed {

    /**
     * One ball carrier's rushing in one game. Yards are whole yards as PFR
     * charts them, and yards before contact can be negative on a carry
     * met behind the line.
     *
     * @param pfrId PFR's player id, the key the id map joins to Sleeper on
     * @param team the carrier's team, in Sleeper's codes
     * @param opponent the defense he ran against, in Sleeper's codes
     * @param brokenTackles tackles he broke on carries; receptions are not counted
     */
    public record RushLine(
            String pfrId,
            String player,
            String gameId,
            int week,
            String team,
            String opponent,
            int carries,
            int yardsBeforeContact,
            int yardsAfterContact,
            int brokenTackles) {
    }
}
