package otto.nflverse;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The stored, trimmed nflverse weekly player stats: one row per player
 * per regular-season game, for the four positions the Player Directory
 * keeps. Stat names are translated to the league's own scoring keys at
 * download time, so everything downstream prices a played week the same
 * way it prices a projected one.
 *
 * @param season the season these rows cover
 * @param priorSeasonFinal true when the current season has no played
 *        week yet, so these rows are last season's final record
 * @param assetUpdatedAt the release timestamp this copy was taken at;
 *        an unchanged value means no download is needed
 * @param checkedAt when the hourly timestamp check last ran
 */
public record WeeklyStats(
        String season,
        boolean priorSeasonFinal,
        Instant assetUpdatedAt,
        Instant checkedAt,
        List<StatLine> rows) implements NflverseFeed {

    /**
     * One player's line from one game, and the defense that allowed it.
     *
     * @param targetShare his share of his team's targets in that game,
     *        nflverse's own {@code target_share} column, or null when
     *        the row carries no readable value. A null is not a zero:
     *        one says the file did not say, the other says the offence
     *        never looked at him.
     */
    public record StatLine(
            String gsisId,
            String player,
            String position,
            String team,
            String opponent,
            int week,
            Double targetShare,
            Map<String, Double> stats) {

        private static final String CARRIES = "rush_att";
        private static final String TARGETS = "rec_tgt";

        /**
         * How much work the offense gave this player: carries plus
         * targets. Against the same total for every back his team
         * played, it is a running back's opportunity share.
         */
        public double touches() {
            return stats.getOrDefault(CARRIES, 0.0) + stats.getOrDefault(TARGETS, 0.0);
        }
    }

    public WeeklyStats withCheckedAt(Instant newCheckedAt) {
        return new WeeklyStats(season, priorSeasonFinal, assetUpdatedAt, newCheckedAt, rows);
    }
}
