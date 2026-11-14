package otto.nflverse;

import java.time.Instant;
import java.util.List;

/**
 * The stored PFR weekly advanced defense file, folded to one row per
 * defense per regular-season game. {@link PressureRates} divides these
 * pressures by the opposing passers' dropbacks.
 *
 * @param season the season these rows cover
 * @param priorSeasonFinal true when the current season has no played
 *        week yet, so these rows are last season's final record
 * @param assetUpdatedAt the release timestamp this copy was taken at
 * @param checkedAt when the hourly timestamp check last ran
 * @param coverage one unit per team per week
 */
public record PfrDefense(
        String season,
        boolean priorSeasonFinal,
        Instant assetUpdatedAt,
        Instant checkedAt,
        Coverage coverage,
        List<GamePressures> rows) implements NflverseFeed {

    /**
     * One defense's game, team codes in Sleeper's vocabulary.
     *
     * @param pressures {@code def_pressures} summed over the defense's
     *        players. Two players who pressure the passer on the same
     *        play each count it, so this can exceed the passers'
     *        {@code times_pressured}.
     */
    public record GamePressures(String gameId, int week, String team, String opponent,
            int pressures) {
    }
}
