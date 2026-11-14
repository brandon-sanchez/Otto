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
import static otto.nflverse.FeedRows.optionalNumber;
import static otto.nflverse.FeedRows.requireColumns;

/**
 * The weekly player stats, one season at a time - the season the
 * defense-versus-position table is built from. Before any 2026 week has
 * been played that is 2025, so week 1 answers from last season's final
 * record instead of from nothing.
 */
final class WeeklyStatsFeed implements FeedSpec<WeeklyStats.StatLine, WeeklyStats> {

    private static final String CARRIES = "carries";
    private static final String TARGETS = "targets";

    /**
     * An nflverse stat column and the Sleeper stat key that names the
     * same thing. Translating here means one vocabulary downstream:
     * {@link otto.lineup.LeagueScoring} prices a played week exactly as
     * it prices a projected one. nflverse's own fantasy-point columns
     * are deliberately absent - no league scoring setting names them.
     */
    private static final Map<String, String> SLEEPER_STAT_KEYS = Map.ofEntries(
            Map.entry(CARRIES, "rush_att"),
            Map.entry(TARGETS, "rec_tgt"),
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

    private static final String TARGET_SHARE = "target_share";

    private static final Set<String> COLUMNS = Set.of(
            "player_id", "player_display_name", "position", "season_type", "team",
            "opponent_team", "week", TARGET_SHARE, "air_yards_share", "wopr", "racr",
            "receiving_epa");

    @Override
    public FeedId id() {
        return FeedId.WEEKLY_STATS;
    }

    @Override
    public String documentName() {
        return "nflverse-weekly-stats";
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
    public Grain<WeeklyStats.StatLine> grain() {
        return new Grain.PerTeamWeek<>(WeeklyStats.StatLine::week, WeeklyStats.StatLine::team);
    }

    @Override
    public List<WeeklyStats.StatLine> read(Basis basis, Stream<Csv.Row> rows) {
        List<AwaitingTeamTotals> kept = new ArrayList<>();
        Map<TeamGame, TeamTotals> teamTotals = new HashMap<>();
        rows.forEach(row -> {
            requireColumns(row, COLUMNS);
            requireColumns(row, SLEEPER_STAT_KEYS.keySet());
            if (!REGULAR_SEASON.equals(row.text("season_type"))) {
                return;
            }
            String team = NflTeams.normalize(row.text("team"));
            int week = row.integer("week");
            teamTotals.merge(new TeamGame(team, week),
                    new TeamTotals(row.integer(TARGETS), row.integer(CARRIES)), TeamTotals::plus);
            String position = row.text("position");
            if (!KEPT_POSITIONS.contains(position) || row.text("player_id").isBlank()) {
                return;
            }
            kept.add(new AwaitingTeamTotals(row.text("player_id"), row.text("player_display_name"), position,
                    team, NflTeams.normalize(row.text("opponent_team")), week,
                    row.integer(CARRIES), targetShare(row),
                    optionalNumber(row, "air_yards_share"), optionalNumber(row, "wopr"),
                    optionalNumber(row, "racr"), optionalNumber(row, "receiving_epa"),
                    statsOf(row)));
        });
        return kept.stream()
                .map(line -> line.withTotals(teamTotals.get(new TeamGame(line.team(), line.week()))))
                .toList();
    }

    private record TeamGame(String team, int week) {
    }

    private record TeamTotals(int targets, int carries) {

        TeamTotals plus(TeamTotals other) {
            return new TeamTotals(targets + other.targets, carries + other.carries);
        }
    }

    private record AwaitingTeamTotals(String gsisId, String player, String position, String team,
            String opponent, int week, int carries, Double targetShare, Double airYardsShare,
            Double wopr, Double racr, Double receivingEpa, Map<String, Double> stats) {

        WeeklyStats.StatLine withTotals(TeamTotals totals) {
            Double carryShare = totals.carries() > 0
                    ? (double) carries / totals.carries() : null;
            return new WeeklyStats.StatLine(gsisId, player, position, team, opponent, week,
                    new WeeklyStats.Usage(totals.targets(), totals.carries(), targetShare,
                            carryShare,
                            airYardsShare, wopr, racr, receivingEpa),
                    stats);
        }
    }

    private static Double targetShare(Csv.Row row) {
        Double share = optionalNumber(row, TARGET_SHARE);
        return share != null && share >= 0.0 && share <= 1.0 ? share : null;
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

    @Override
    public int contentVersion() {
        return 2;
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
    public WeeklyStats recheck(WeeklyStats current, Basis basis, Instant checkedAt) {
        return new WeeklyStats(basis.season(), basis.priorSeasonFinal(), current.assetUpdatedAt(),
                checkedAt, current.coverage(), current.rows());
    }

    @Override
    public Class<WeeklyStats> type() {
        return WeeklyStats.class;
    }
}
