package otto.nflverse;

import java.time.Instant;
import java.util.List;

/**
 * The stored, trimmed nflverse depth charts: the most recent published
 * chart per team, for the four positions the Player Directory keeps.
 * The published file carries every chart of the season, one per
 * publish date; only the newest per team says anything about this week.
 *
 * @param assetUpdatedAt the release timestamp this copy was taken at
 * @param checkedAt when the hourly timestamp check last ran
 * @param coverage which units the rows hold and when each last changed
 */
public record DepthCharts(
        String season,
        Instant assetUpdatedAt,
        Instant checkedAt,
        Coverage coverage,
        List<Spot> rows) implements NflverseFeed {

    /**
     * One player's place on their team's chart: RB1, WR3 and so on.
     *
     * The rank he held on the chart published before this one rides
     * alongside, because a waiver score turns on the move rather than
     * on the standing: RB2 to RB1 is the news, RB1 again is not. A
     * player absent from the previous chart carries rank 0.
     *
     * @param chartedAt when the chart this place comes from was published
     */
    public record Spot(String gsisId, String player, String team, String position, int rank,
            int previousRank, Instant chartedAt) {

        /** How the user would say it: "RB1". */
        public String label() {
            return position + rank;
        }

        /** True when this chart moved him up from the one before it. */
        public boolean promoted() {
            return previousRank > 0 && rank < previousRank;
        }
    }
}
