package otto.nflverse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static otto.nflverse.FeedRows.KEPT_POSITIONS;
import static otto.nflverse.FeedRows.REGULAR_SEASON;
import static otto.nflverse.FeedRows.blankOrNa;
import static otto.nflverse.FeedRows.requireColumns;

/**
 * One Next Gen Stats leaderboard. nflverse publishes no per-season file
 * for recent seasons, only one gzipped file per leaderboard holding every
 * season since 2016, so the asset name never changes and the season is
 * chosen by filtering rows: the Sleeper season and the one before it.
 *
 * A leaderboard carries no game id, and a team whose players all fell
 * short of the minimum has no row that week, so neither a game nor a
 * team-week can say whether a week arrived. The unit is the whole
 * season-week, which the NFL publishes at once.
 */
final class NextGenStatsFeed implements FeedSpec<NextGenStats.Line, NextGenStats> {

    private static final Set<String> COLUMNS = Set.of(
            "season", "season_type", "week", "player_gsis_id", "player_display_name",
            "player_position", "team_abbr");

    private final NextGenStats.Kind kind;

    NextGenStatsFeed(NextGenStats.Kind kind) {
        this.kind = kind;
    }

    @Override
    public FeedId id() {
        return kind.id();
    }

    @Override
    public String documentName() {
        return kind.documentName();
    }

    @Override
    public String tag() {
        return "nextgen_stats";
    }

    @Override
    public String asset(String season) {
        return "ngs_%s.csv.gz".formatted(kind.statType());
    }

    @Override
    public SeasonRule seasonRule() {
        return SeasonRule.CURRENT;
    }

    @Override
    public Grain<NextGenStats.Line> grain() {
        return new Grain.PerWeek<>(NextGenStats.Line::season, NextGenStats.Line::week);
    }

    @Override
    public List<NextGenStats.Line> read(Basis basis, Stream<Csv.Row> rows) {
        Set<String> kept = Seasons.previous(basis.season())
                .map(previous -> Set.of(basis.season(), previous))
                .orElse(Set.of(basis.season()));
        List<NextGenStats.Line> lines = new ArrayList<>();
        rows.forEach(row -> {
            requireColumns(row, COLUMNS);
            requireColumns(row, kind.metrics());
            String gsisId = row.text("player_gsis_id");
            String position = row.text("player_position");
            if (!kept.contains(row.text("season"))
                    || !REGULAR_SEASON.equals(row.text("season_type"))
                    || !KEPT_POSITIONS.contains(position)
                    || blankOrNa(gsisId)
                    || blankOrNa(row.text("week"))) {
                return;
            }
            lines.add(new NextGenStats.Line(row.text("season"), row.integer("week"), gsisId,
                    row.text("player_display_name"), position,
                    NflTeams.normalize(row.text("team_abbr")), metrics(row)));
        });
        return lines;
    }

    /**
     * The kind's metrics the row recorded. A blank, "NA", unparsable or
     * non-finite value is left out, so it reads as unknown downstream;
     * a published zero is kept, because it is a measurement.
     */
    private Map<String, Double> metrics(Csv.Row row) {
        Map<String, Double> metrics = new HashMap<>();
        kind.metrics().forEach(column -> {
            String value = row.text(column).trim();
            if (blankOrNa(value)) {
                return;
            }
            try {
                double parsed = Double.parseDouble(value);
                if (Double.isFinite(parsed)) {
                    metrics.put(column, parsed);
                }
            } catch (NumberFormatException ignored) {
            }
        });
        return Map.copyOf(metrics);
    }

    @Override
    public NextGenStats document(Basis basis, Instant assetUpdatedAt, Instant checkedAt,
            List<NextGenStats.Line> rows, Coverage coverage) {
        Map<Boolean, List<NextGenStats.Line>> totals = rows.stream()
                .collect(Collectors.partitioningBy(
                        line -> line.week() == NextGenStats.SEASON_TOTAL_WEEK));
        return new NextGenStats(basis.season(), assetUpdatedAt, checkedAt, coverage,
                List.copyOf(totals.get(false)), List.copyOf(totals.get(true)));
    }

    @Override
    public NextGenStats recheck(NextGenStats current, Basis basis, Instant checkedAt) {
        return new NextGenStats(basis.season(), current.assetUpdatedAt(), checkedAt,
                current.coverage(), current.weeks(), current.seasonTotals());
    }

    @Override
    public Class<NextGenStats> type() {
        return NextGenStats.class;
    }
}
