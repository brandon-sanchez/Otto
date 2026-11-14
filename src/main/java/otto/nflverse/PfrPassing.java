package otto.nflverse;

import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;

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
     * @param timesPressuredPct PFR's {@code times_pressured_pct}, pressures
     *        per dropback from 0 to 1, or null when the file gave no
     *        readable value
     */
    public record PasserLine(String gameId, int week, String team, String opponent,
            String pfrId, String player, int timesPressured, Double timesPressuredPct) {

        /**
         * His dropbacks, recovered from PFR's own ratio. The file carries
         * no dropback column, and PFR divides pressures by dropbacks, so
         * the count is the pressures over the percentage. The percentage
         * is published to three places, so the quotient is rounded to the
         * nearest whole dropback.
         *
         * @return empty when he was never pressured: a zero percentage
         *         says nothing about how often he dropped back
         */
        public OptionalInt dropbacks() {
            if (timesPressured <= 0 || timesPressuredPct == null || timesPressuredPct <= 0.0) {
                return OptionalInt.empty();
            }
            return OptionalInt.of((int) Math.round(timesPressured / timesPressuredPct));
        }
    }
}
