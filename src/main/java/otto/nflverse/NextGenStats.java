package otto.nflverse;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One Next Gen Stats leaderboard (passing, receiving or rushing) for the
 * current regular season and the one before it.
 *
 * The NFL publishes a leaderboard row only for a player who cleared its
 * qualifying minimum that week or season, and Otto keeps exactly the
 * rows it publishes. A player with no row is unknown: he may have played
 * and fallen short of the minimum, so his absence is never read as zero.
 *
 * Season totals arrive in the same file as week 0. They are kept in
 * their own list, so a weekly query can never answer with a total.
 *
 * @param season the Sleeper season this copy was taken for; the rows
 *        cover it and the season before
 * @param assetUpdatedAt the release timestamp this copy was taken at
 * @param checkedAt when the hourly timestamp check last ran
 * @param coverage one unit per season and week, the totals as week 0
 * @param weeks the weekly rows, weeks 1 and later
 * @param seasonTotals the season-to-date rows, one per qualifying player and season
 */
public record NextGenStats(
        String season,
        Instant assetUpdatedAt,
        Instant checkedAt,
        Coverage coverage,
        List<Line> weeks,
        List<Line> seasonTotals) implements NflverseFeed {

    static final int SEASON_TOTAL_WEEK = 0;

    /**
     * One player's row on one leaderboard.
     *
     * @param week the week, or 0 for a season total
     * @param team the team in Sleeper's codes
     * @param metrics the kind's metric columns by their nflverse names. A
     *        metric the row left blank is absent, never zero.
     */
    public record Line(String season, int week, String gsisId, String player, String position,
            String team, Map<String, Double> metrics) {
    }

    /** Which leaderboard a document holds, and the columns Otto keeps from it. */
    public enum Kind {

        PASSING(FeedId.NGS_PASSING, "passing", "nflverse-ngs-passing", Set.of(
                "attempts", "avg_time_to_throw", "aggressiveness", "avg_intended_air_yards",
                "avg_air_yards_to_sticks", "completion_percentage_above_expectation")),

        RECEIVING(FeedId.NGS_RECEIVING, "receiving", "nflverse-ngs-receiving", Set.of(
                "targets", "avg_cushion", "avg_separation", "avg_intended_air_yards",
                "percent_share_of_intended_air_yards", "avg_yac_above_expectation")),

        RUSHING(FeedId.NGS_RUSHING, "rushing", "nflverse-ngs-rushing", Set.of(
                "rush_attempts", "efficiency", "percent_attempts_gte_eight_defenders",
                "avg_time_to_los", "rush_yards_over_expected", "rush_yards_over_expected_per_att",
                "rush_pct_over_expected"));

        private final FeedId id;
        private final String statType;
        private final String documentName;
        private final Set<String> metrics;

        Kind(FeedId id, String statType, String documentName, Set<String> metrics) {
            this.id = id;
            this.statType = statType;
            this.documentName = documentName;
            this.metrics = metrics;
        }

        FeedId id() {
            return id;
        }

        String statType() {
            return statType;
        }

        String documentName() {
            return documentName;
        }

        Set<String> metrics() {
            return metrics;
        }
    }

    /**
     * Every row published for one week, in file order.
     *
     * @throws IllegalArgumentException when the week is 0: totals are
     *         read through {@link #seasonTotals}
     */
    public List<Line> week(String season, int week) {
        if (week == SEASON_TOTAL_WEEK) {
            throw new IllegalArgumentException("week 0 is the season total, not a week");
        }
        return weeks.stream()
                .filter(line -> line.season().equals(season) && line.week() == week)
                .toList();
    }

    /**
     * A Sleeper player's row for one week, joined through the id map.
     * Empty when the player has no GSIS id in the map or no row that
     * week, which says only that the leaderboard did not list him.
     *
     * @throws IllegalArgumentException when the week is 0
     */
    public Optional<Line> week(PlayerIdMap ids, String sleeperId, String season, int week) {
        List<Line> published = week(season, week);
        return ids.gsisFor(sleeperId).flatMap(gsisId -> published.stream()
                .filter(line -> line.gsisId().equals(gsisId))
                .findFirst());
    }

    /** A Sleeper player's season-to-date row, or empty when the leaderboard did not list him. */
    public Optional<Line> seasonTotal(PlayerIdMap ids, String sleeperId, String season) {
        return ids.gsisFor(sleeperId).flatMap(gsisId -> seasonTotals.stream()
                .filter(line -> line.season().equals(season) && line.gsisId().equals(gsisId))
                .findFirst());
    }
}
