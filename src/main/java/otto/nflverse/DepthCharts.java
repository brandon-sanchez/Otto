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
 */
public record DepthCharts(
        String season,
        Instant assetUpdatedAt,
        Instant checkedAt,
        List<Spot> rows) implements NflverseFeed {

    /**
     * One player's place on their team's chart: RB1, WR3 and so on.
     *
     * The rank he held on the chart published before this one rides
     * alongside, because a waiver score turns on the move rather than
     * on the standing: RB2 to RB1 is the news, RB1 again is not. A
     * player absent from the previous chart carries rank 0.
     */
    public record Spot(String gsisId, String player, String team, String position, int rank,
            int previousRank) {

        /** How the user would say it: "RB1". */
        public String label() {
            return position + rank;
        }

        /** True when this chart moved him up from the one before it. */
        public boolean promoted() {
            return previousRank > 0 && rank < previousRank;
        }
    }

    public DepthCharts withCheckedAt(Instant newCheckedAt) {
        return new DepthCharts(season, assetUpdatedAt, newCheckedAt, rows);
    }
}
