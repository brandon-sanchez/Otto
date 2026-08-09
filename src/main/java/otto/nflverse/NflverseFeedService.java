package otto.nflverse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.alerts.SelfReportService;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;

/**
 * Keeps the nflverse feeds current: an hourly check of the release
 * index, and a download only when an asset's publish timestamp has
 * moved. Shaped like {@link otto.directory.PlayerDirectoryService} -
 * one update method, gated by its own interval, returning what each
 * source did. The season files run to tens of megabytes and are republished
 * at most once a day, so the timestamp is what makes an hourly cadence
 * affordable.
 *
 * The weekly stats track one season at a time - the season the
 * defense-versus-position table is built from. Before any 2026 week has
 * been played that is 2025, so week 1 answers from last season's final
 * record instead of from nothing.
 */
@Component
public class NflverseFeedService {

    private static final String WEEKLY_STATS_RELEASE = "stats_player";
    private static final String DEPTH_CHARTS_RELEASE = "depth_charts";

    private static final Set<String> KEPT_POSITIONS = Set.of("QB", "RB", "WR", "TE");
    private static final String REGULAR_SEASON = "REG";

    /** Sleeper writes the season as a four-digit year. */
    private static final Pattern SEASON = Pattern.compile("\\d{4}");

    /**
     * An nflverse stat column and the Sleeper stat key that names the
     * same thing. Translating here means one vocabulary downstream:
     * {@link otto.lineup.LeagueScoring} prices a played week exactly as
     * it prices a projected one. nflverse's own fantasy-point columns
     * are deliberately absent - no league scoring setting names them.
     */
    private static final Map<String, String> SLEEPER_STAT_KEYS = Map.ofEntries(
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
     * Columns each file must carry. These feeds gain and reorder
     * columns between seasons, which a name-keyed read survives - but a
     * renamed column would read as a blank field and quietly price a
     * week at zero. Missing columns become a typed unavailable result
     * and a self-report instead.
     */
    private static final Set<String> WEEKLY_STATS_COLUMNS = Set.of(
            "player_id", "player_display_name", "position", "season_type", "team",
            "opponent_team", "week");
    private static final Set<String> DEPTH_CHART_COLUMNS = Set.of(
            "dt", "team", "player_name", "gsis_id", "pos_abb", "pos_rank");
    private static final Set<String> PLAYER_ID_COLUMNS = Set.of(
            "sleeper_id", "gsis_id", "position");

    private final NflverseClient client;
    private final NflverseStore store;
    private final SleeperAdapter sleeper;
    private final SelfReportService selfReport;
    private final Clock clock;
    private final Duration checkInterval;

    public NflverseFeedService(NflverseClient client, NflverseStore store, SleeperAdapter sleeper,
            SelfReportService selfReport, Clock clock, OttoProperties properties) {
        this.client = client;
        this.store = store;
        // This job is not the Check, so it has no business polling: the
        // week it needs is whatever the Check last saw.
        this.sleeper = sleeper.cachedWithin(properties.sleeperCadence());
        this.selfReport = selfReport;
        this.clock = clock;
        this.checkInterval = properties.nflverse().checkInterval();
    }

    /** What one source did on this run. */
    public sealed interface Update {

        record Skipped() implements Update {
        }

        record Unchanged() implements Update {
        }

        record Downloaded(int rows) implements Update {
        }

        record Unavailable(String source, String reason) implements Update {
        }
    }

    public record Result(Update weeklyStats, Update depthCharts, Update playerIds) {
    }

    /**
     * Runs the timestamp checks whose interval has elapsed. One broken
     * feed self-reports and leaves the others to finish their work.
     */
    public Result updateIfDue() {
        Instant now = clock.instant();
        return new Result(
                report(updateWeeklyStats(now)),
                report(updateDepthCharts(now)),
                report(updatePlayerIds(now)));
    }

    private Update report(Update update) {
        if (update instanceof Update.Unavailable unavailable) {
            selfReport.report(unavailable.source(), unavailable.reason());
        }
        return update;
    }

    private boolean due(Optional<? extends NflverseFeed> stored, Instant now) {
        return stored
                .map(feed -> Duration.between(feed.checkedAt(), now).compareTo(checkInterval) >= 0)
                .orElse(true);
    }

    /** What the timestamp check says to do about one release asset. */
    private sealed interface Decision {

        /** The stored copy is still the published one; move its clock. */
        record Touch() implements Decision {
        }

        record Download(Instant assetUpdatedAt) implements Decision {
        }

        record Blocked(String source, String reason) implements Decision {
        }
    }

    /**
     * The timestamp check, defined once for every release asset: read
     * the release index, and download only when the stored copy did not
     * come from the same season's file at the same publish time - in
     * which case the bytes on the wire are the bytes already on disk and
     * only the checked-at time needs moving.
     */
    private Decision decide(String releaseTag, String asset,
            Optional<? extends NflverseFeed> stored, String season) {
        SourceResult<Instant> published = publishedAt(releaseTag, asset);
        if (published instanceof SourceResult.Unavailable<Instant> unavailable) {
            return new Decision.Blocked(unavailable.source(), unavailable.reason());
        }
        Instant assetUpdatedAt = ((SourceResult.Ok<Instant>) published).value();
        boolean stillCurrent = stored
                .filter(feed -> feed.season().equals(season))
                .filter(feed -> feed.assetUpdatedAt().equals(assetUpdatedAt))
                .isPresent();
        return stillCurrent ? new Decision.Touch() : new Decision.Download(assetUpdatedAt);
    }

    // -- weekly player stats ------------------------------------------------

    /** The season the defense-versus-position table is built from. */
    private record Basis(String season, boolean priorSeasonFinal) {
    }

    private Update updateWeeklyStats(Instant now) {
        Optional<WeeklyStats> stored = store.weeklyStats();
        if (!due(stored, now)) {
            return new Update.Skipped();
        }

        SourceResult<SleeperAdapter.NflState> state = sleeper.nflState();
        if (state instanceof SourceResult.Unavailable<SleeperAdapter.NflState> unavailable) {
            return new Update.Unavailable(unavailable.source(), unavailable.reason());
        }
        SleeperAdapter.NflState nflWeek = ((SourceResult.Ok<SleeperAdapter.NflState>) state).value();
        Optional<Basis> resolved = basisFor(nflWeek);
        if (resolved.isEmpty()) {
            return new Update.Unavailable("sleeper:/v1/state/nfl",
                    "season \"%s\" is not a year, so I cannot name last season's file"
                            .formatted(nflWeek.season()));
        }
        Basis basis = resolved.get();
        String asset = "stats_player_week_%s.csv".formatted(basis.season());

        return switch (decide(WEEKLY_STATS_RELEASE, asset, stored, basis.season())) {
            case Decision.Blocked blocked ->
                new Update.Unavailable(blocked.source(), blocked.reason());
            case Decision.Touch ignored -> {
                // The rows have not moved, but the week may have: a
                // season that was "to date" in week 18 is "final" once
                // the next season's week 1 comes round, and the same
                // file answers for both.
                WeeklyStats current = stored.orElseThrow();
                store.writeWeeklyStats(new WeeklyStats(current.season(),
                        basis.priorSeasonFinal(), current.assetUpdatedAt(), now,
                        current.rows()));
                yield new Update.Unchanged();
            }
            case Decision.Download download -> stored(
                    client.downloadAsset(WEEKLY_STATS_RELEASE, asset,
                            NflverseFeedService::statLines),
                    rows -> store.writeWeeklyStats(new WeeklyStats(basis.season(),
                            basis.priorSeasonFinal(), download.assetUpdatedAt(), now, rows)));
        };
    }

    /**
     * Week 1 has no played week of its own, so the table is last
     * season's final record. Every later week reads the current season
     * to date.
     */
    private static Optional<Basis> basisFor(SleeperAdapter.NflState state) {
        if (state.week() > 1) {
            return Optional.of(new Basis(state.season(), false));
        }
        // Sleeper writes the season as text, so a drifted value must not
        // throw out of a job that still has other feeds to update.
        if (!SEASON.matcher(state.season()).matches()) {
            return Optional.empty();
        }
        return Optional.of(new Basis(
                String.valueOf(Integer.parseInt(state.season()) - 1), true));
    }

    private static List<WeeklyStats.StatLine> statLines(Stream<Csv.Row> rows) {
        List<WeeklyStats.StatLine> lines = new ArrayList<>();
        rows.forEach(row -> {
            requireColumns(row, WEEKLY_STATS_COLUMNS);
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
                    statsOf(row)));
        });
        return lines;
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

    // -- depth charts -------------------------------------------------------

    private Update updateDepthCharts(Instant now) {
        Optional<DepthCharts> stored = store.depthCharts();
        if (!due(stored, now)) {
            return new Update.Skipped();
        }

        SourceResult<SleeperAdapter.NflState> state = sleeper.nflState();
        if (state instanceof SourceResult.Unavailable<SleeperAdapter.NflState> unavailable) {
            return new Update.Unavailable(unavailable.source(), unavailable.reason());
        }
        String season = ((SourceResult.Ok<SleeperAdapter.NflState>) state).value().season();
        String asset = "depth_charts_%s.csv".formatted(season);

        return switch (decide(DEPTH_CHARTS_RELEASE, asset, stored, season)) {
            case Decision.Blocked blocked ->
                new Update.Unavailable(blocked.source(), blocked.reason());
            case Decision.Touch ignored -> {
                store.writeDepthCharts(stored.orElseThrow().withCheckedAt(now));
                yield new Update.Unchanged();
            }
            case Decision.Download download -> stored(
                    client.downloadAsset(DEPTH_CHARTS_RELEASE, asset,
                            NflverseFeedService::depthChart),
                    rows -> store.writeDepthCharts(new DepthCharts(season,
                            download.assetUpdatedAt(), now, rows)));
        };
    }

    /**
     * The published file holds every chart of the season, one snapshot
     * per date. Only the newest snapshot per team describes this week,
     * so the rest is dropped on the way in.
     */
    private static List<DepthCharts.Spot> depthChart(Stream<Csv.Row> rows) {
        Map<String, String> newestDate = new HashMap<>();
        Map<String, List<DepthCharts.Spot>> byTeam = new LinkedHashMap<>();
        rows.forEach(row -> {
            requireColumns(row, DEPTH_CHART_COLUMNS);
            String position = row.text("pos_abb");
            String team = NflTeams.normalize(row.text("team"));
            String published = row.text("dt");
            // A blank or "NA" rank reads as zero, and "RB0" is not a
            // depth-chart place anyone would recognise.
            if (!KEPT_POSITIONS.contains(position) || team.isBlank()
                    || row.text("gsis_id").isBlank() || row.integer("pos_rank") < 1) {
                return;
            }
            String newest = newestDate.get(team);
            if (newest == null || published.compareTo(newest) > 0) {
                newestDate.put(team, published);
                byTeam.put(team, new ArrayList<>());
            } else if (published.compareTo(newest) < 0) {
                return;
            }
            byTeam.get(team).add(new DepthCharts.Spot(
                    row.text("gsis_id"),
                    row.text("player_name"),
                    team,
                    position,
                    row.integer("pos_rank")));
        });
        return byTeam.values().stream().flatMap(List::stream).toList();
    }

    // -- player id mapping --------------------------------------------------

    private Update updatePlayerIds(Instant now) {
        Optional<PlayerIdMap> stored = store.playerIds();
        if (stored.isPresent() && Duration.between(stored.get().checkedAt(), now)
                .compareTo(checkInterval) < 0) {
            return new Update.Skipped();
        }

        SourceResult<NflverseClient.Downloaded<Map<String, String>>> result =
                client.downloadPlayerIds(stored.map(PlayerIdMap::etag).orElse(null),
                        NflverseFeedService::sleeperToGsis);
        return switch (result) {
            case SourceResult.Unavailable<NflverseClient.Downloaded<Map<String, String>>> unavailable ->
                new Update.Unavailable(unavailable.source(), unavailable.reason());
            case SourceResult.Ok<NflverseClient.Downloaded<Map<String, String>>> ok -> {
                if (ok.value().notModified()) {
                    if (stored.isEmpty()) {
                        yield new Update.Unavailable("nflverse:player-ids",
                                "304 without a stored mapping");
                    }
                    store.writePlayerIds(stored.get().withCheckedAt(now));
                    yield new Update.Unchanged();
                }
                store.writePlayerIds(new PlayerIdMap(ok.value().etag(), now, ok.value().value()));
                yield new Update.Downloaded(ok.value().value().size());
            }
        };
    }

    private static Map<String, String> sleeperToGsis(Stream<Csv.Row> rows) {
        Map<String, String> mapping = new HashMap<>();
        rows.forEach(row -> {
            requireColumns(row, PLAYER_ID_COLUMNS);
            String sleeperId = row.text("sleeper_id");
            String gsisId = row.text("gsis_id");
            if (blankOrNa(sleeperId) || blankOrNa(gsisId)
                    || !KEPT_POSITIONS.contains(row.text("position"))) {
                return;
            }
            mapping.put(sleeperId, gsisId);
        });
        return mapping;
    }

    /** The published files write a missing value as the string "NA". */
    private static boolean blankOrNa(String value) {
        return value.isBlank() || "NA".equals(value);
    }

    // -- shared -------------------------------------------------------------

    /**
     * Fails the whole download when the file no longer carries a column
     * this code reads. The client turns the throw into a typed
     * unavailable result, so drift self-reports rather than silently
     * pricing a week at zero.
     */
    private static void requireColumns(Csv.Row row, Set<String> required) {
        List<String> missing = required.stream()
                .filter(column -> !row.columns().containsKey(column))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("schema drift: missing columns " + missing);
        }
    }

    /** Stores a downloaded feed, or reports why it never arrived. */
    private static <T> Update stored(SourceResult<List<T>> rows, Consumer<List<T>> write) {
        return switch (rows) {
            case SourceResult.Unavailable<List<T>> unavailable ->
                new Update.Unavailable(unavailable.source(), unavailable.reason());
            case SourceResult.Ok<List<T>> ok -> {
                write.accept(ok.value());
                yield new Update.Downloaded(ok.value().size());
            }
        };
    }

    private SourceResult<Instant> publishedAt(String releaseTag, String asset) {
        return client.assetTimestamps(releaseTag).flatMap(timestamps -> {
            Instant published = timestamps.get(asset);
            if (published == null) {
                return new SourceResult.Unavailable<>("nflverse:" + releaseTag,
                        "the release carries no asset named " + asset);
            }
            return new SourceResult.Ok<>(published);
        });
    }
}
