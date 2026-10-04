package otto.nflverse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static otto.nflverse.FeedRows.KEPT_POSITIONS;
import static otto.nflverse.FeedRows.REGULAR_SEASON;
import static otto.nflverse.FeedRows.blankOrNa;
import static otto.nflverse.FeedRows.requireColumns;

/**
 * The weekly player stats, one season at a time - the season the
 * defense-versus-position table is built from. Before any 2026 week has
 * been played that is 2025, so week 1 answers from last season's final
 * record instead of from nothing.
 */
final class WeeklyStatsFeed implements FeedSpec<WeeklyStats.StatLine, WeeklyStats> {

    /**
     * An nflverse stat column and the Sleeper stat key that names the
     * same thing. Translating here means one vocabulary downstream:
     * {@link otto.lineup.LeagueScoring} prices a played week exactly as
     * it prices a projected one. nflverse's own fantasy-point columns
     * are deliberately absent - no league scoring setting names them.
     */
    private static final Map<String, String> SLEEPER_STAT_KEYS = Map.ofEntries(
            Map.entry("carries", "rush_att"),
            Map.entry("targets", "rec_tgt"),
            Map.entry("passing_yards", "pass_yd"),
            Map.entry("passing_tds", "pass_td"),
            Map.entry("passing_interceptions", "pass_int"),
            Map.entry("passing_2pt_conversions", "pass_2pt"),
            Map.entry("rushing_yards", "rush_yd"),
            Map.entry("rushing_tds", "rush_td"),
            Map.entry("rushing_2pt_conversions", "rush_2pt"),
            Map.entry("receptions", "rec"),
            Map.entry("receiving_yards", "rec_yd"),
            Map.entry("receiving_tds", "rec_td"),
            Map.entry("receiving_2pt_conversions", "rec_2pt"),
            Map.entry("fumbles_lost_total", "fum_lost"));

    /**
     * The share of his team's targets a player saw in one game. It is
     * nflverse's own column, computed against a real team denominator,
     * and it is what the waiver breakout tag reads for receivers and
     * tight ends. It is no league's scoring key, so it rides beside the
     * translated stats rather than inside them.
     */
    private static final String TARGET_SHARE = "target_share";

    private static final Set<String> COLUMNS = Set.of(
            "player_id", "player_display_name", "position", "season_type", "team",
            "opponent_team", "week", TARGET_SHARE);

    @Override
    public FeedId id() {
        return FeedId.WEEKLY_STATS;
    }

    @Override
    public String tag() {
        return "stats_player";
    }

    @Override
    public String asset(String season) {
        return "stats_player_week_%s.csv".formatted(season);
    }

    @Override
    public SeasonRule seasonRule() {
        return SeasonRule.LAST_PLAYED;
    }

    @Override
    public Grain grain() {
        return Grain.TEAM_WEEK;
    }

    @Override
    public List<WeeklyStats.StatLine> read(Basis basis, Stream<Csv.Row> rows) {
        List<WeeklyStats.StatLine> lines = new ArrayList<>();
        rows.forEach(row -> {
            requireColumns(row, COLUMNS);
            requireColumns(row, SLEEPER_STAT_KEYS.keySet());
            String position = row.text("position");
            if (!KEPT_POSITIONS.contains(position)
                    || !REGULAR_SEASON.equals(row.text("season_type"))
                    || row.text("player_id").isBlank()) {
                return;
            }
            lines.add(new WeeklyStats.StatLine(
                    row.text("player_id"),
                    row.text("player_display_name"),
                    position,
                    NflTeams.normalize(row.text("team")),
                    NflTeams.normalize(row.text("opponent_team")),
                    row.integer("week"),
                    targetShare(row),
                    statsOf(row)));
        });
        return lines;
    }

    /**
     * Keyed by the team's week rather than by game: every row names its
     * team, and a team plays once a week.
     */
    @Override
    public Coverage.Unit unit(WeeklyStats.StatLine line) {
        return new Coverage.Unit(line.week(), line.team());
    }

    /**
     * The published target share, or nothing when the row does not
     * carry one. A blank, an "NA" or a value that is not a number reads
     * as absent rather than as zero: a zero would say the offence never
     * looked at him, which is a claim this file did not make.
     *
     * A value outside nought to one is refused for the same reason,
     * and so are "NaN" and "Infinity", which both parse as doubles. A
     * share of more than the whole offence would clear every bar the
     * breakout lanes hold, which is the one wrong answer this column
     * could produce on its own.
     */
    private static Double targetShare(Csv.Row row) {
        String value = row.text(TARGET_SHARE).trim();
        if (blankOrNa(value)) {
            return null;
        }
        try {
            double share = Double.parseDouble(value);
            return share >= 0.0 && share <= 1.0 ? share : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** Only the stats the row actually recorded; a zero says nothing. */
    private static Map<String, Double> statsOf(Csv.Row row) {
        Map<String, Double> stats = new HashMap<>();
        SLEEPER_STAT_KEYS.forEach((column, sleeperKey) -> {
            double value = row.number(column);
            if (value != 0.0) {
                stats.put(sleeperKey, value);
            }
        });
        return stats;
    }

    /**
     * Built from the basis on a re-check too, not only on a download:
     * a season that was "to date" in week 18 is "final" once the next
     * season's week 1 comes round, and the same file answers for both.
     */
    @Override
    public WeeklyStats document(Basis basis, Instant assetUpdatedAt, Instant checkedAt,
            List<WeeklyStats.StatLine> rows, Coverage coverage) {
        return new WeeklyStats(basis.season(), basis.priorSeasonFinal(), assetUpdatedAt, checkedAt,
                coverage, rows);
    }

    @Override
    public Class<WeeklyStats> type() {
        return WeeklyStats.class;
    }
}
