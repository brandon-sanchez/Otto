package otto.nflverse;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static otto.nflverse.FeedRows.KEPT_POSITIONS;
import static otto.nflverse.FeedRows.requireColumns;

final class DepthChartsFeed implements FeedSpec<DepthCharts.Spot, DepthCharts> {

    private static final Set<String> COLUMNS = Set.of(
            "dt", "team", "player_name", "gsis_id", "pos_abb", "pos_rank");

    /**
     * The shapes the depth charts' publish column has been seen in: an
     * instant with a zone offset, a bare local timestamp, or a bare
     * date. All three name an unambiguous moment, so all three order
     * correctly against each other. A bare date is a day in the NFL's
     * own zone, the one the schedule places a week's window in, so a
     * chart dated the Tuesday a week opens falls inside that week.
     */
    private static final List<Function<String, Instant>> PUBLISH_FORMATS = List.of(
            published -> OffsetDateTime.parse(published).toInstant(),
            published -> LocalDateTime.parse(published).toInstant(ZoneOffset.UTC),
            published -> LocalDate.parse(published).atStartOfDay(ScheduleFeed.EASTERN).toInstant());

    @Override
    public FeedId id() {
        return FeedId.DEPTH_CHARTS;
    }

    @Override
    public String documentName() {
        return "nflverse-depth-charts";
    }

    @Override
    public String tag() {
        return "depth_charts";
    }

    @Override
    public String asset(String season) {
        return "depth_charts_%s.csv".formatted(season);
    }

    @Override
    public SeasonRule seasonRule() {
        return SeasonRule.CURRENT;
    }

    @Override
    public Grain<DepthCharts.Spot> grain() {
        return new Grain.PerTeam<>(DepthCharts.Spot::team);
    }

    /** A team sets its chart for a week before that week's game, not after it. */
    @Override
    public Due due() {
        return Due.WHEN_SCHEDULED;
    }

    /**
     * The published file holds every chart of the season, one snapshot
     * per date. Only the newest snapshot per team describes this week,
     * so the rest is dropped on the way in - except the one published
     * before it, which is kept only long enough to say which players
     * moved up. Nothing else about the older chart is stored.
     */
    @Override
    public List<DepthCharts.Spot> read(Basis basis, Stream<Csv.Row> rows) {
        Map<String, TeamChart> byTeam = new LinkedHashMap<>();
        rows.forEach(row -> {
            requireColumns(row, COLUMNS);
            String position = row.text("pos_abb");
            String team = NflTeams.normalize(row.text("team"));
            // A blank or "NA" rank reads as zero, and "RB0" is not a
            // depth-chart place anyone would recognise.
            if (!KEPT_POSITIONS.contains(position) || team.isBlank()
                    || row.text("gsis_id").isBlank() || row.integer("pos_rank") < 1) {
                return;
            }
            byTeam.computeIfAbsent(team, TeamChart::new).add(publishedAt(row.text("dt")),
                    row.text("gsis_id"), row.text("player_name"), position,
                    row.integer("pos_rank"));
        });
        return byTeam.values().stream().flatMap(chart -> chart.spots().stream()).toList();
    }

    /**
     * The moment a chart was published, read as a point in time rather
     * than as the text nflverse happened to write.
     *
     * Which chart is newest decides which one describes this week, and
     * comparing the raw strings only agrees with the calendar while the
     * format never moves. A day nflverse writes "2026-09-08" beside
     * "2026-09-15T07:30:00Z", or an offset beside a Z, would silently
     * reorder the snapshots - and the rank a player held on the chart
     * before this one is what the waiver score reads as a promotion.
     *
     * A date this code cannot read is drift, so it fails the download
     * the same way a renamed column does. Leaving it to sort as text
     * would keep the feed and lose the meaning.
     */
    private static Instant publishedAt(String value) {
        String published = value.trim();
        if (published.isBlank() || "NA".equals(published)) {
            throw new IllegalStateException("schema drift: a depth-chart row carries no dt");
        }
        for (Function<String, Instant> format : PUBLISH_FORMATS) {
            try {
                return format.apply(published);
            } catch (DateTimeParseException wrongFormat) {
                // Try the next shape this column has been seen in.
            }
        }
        throw new IllegalStateException(
                "schema drift: dt \"%s\" is not a date this code can read".formatted(published));
    }

    @Override
    public Optional<Instant> stamp(DepthCharts.Spot spot) {
        return Optional.of(spot.chartedAt());
    }

    /** The rank he held on the chart before is that older chart's content, not this one's. */
    @Override
    public Object content(DepthCharts.Spot spot) {
        return List.of(spot.gsisId(), spot.player(), spot.position(), spot.rank());
    }

    @Override
    public DepthCharts document(Basis basis, Instant assetUpdatedAt, Instant checkedAt,
            List<DepthCharts.Spot> rows, Coverage coverage) {
        return new DepthCharts(basis.season(), assetUpdatedAt, checkedAt, coverage, rows);
    }

    @Override
    public DepthCharts recheck(DepthCharts current, Basis basis, Instant checkedAt) {
        return new DepthCharts(basis.season(), current.assetUpdatedAt(), checkedAt,
                current.coverage(), current.rows());
    }

    @Override
    public Class<DepthCharts> type() {
        return DepthCharts.class;
    }

    /**
     * The two newest charts one team published, held while the file
     * streams past. Rows arrive in whatever order the file lists them,
     * so both slots are found by comparing publish dates rather than
     * by trusting the order.
     */
    private static final class TeamChart {

        private record Row(String gsisId, String player, String position, int rank) {
        }

        private final String team;
        private Instant newestDate;
        private Instant previousDate;
        private List<Row> newest = new ArrayList<>();
        private List<Row> previous = new ArrayList<>();

        TeamChart(String team) {
            this.team = team;
        }

        void add(Instant published, String gsisId, String player, String position, int rank) {
            Row row = new Row(gsisId, player, position, rank);
            if (newestDate == null || published.isAfter(newestDate)) {
                previousDate = newestDate;
                previous = newest;
                newestDate = published;
                newest = new ArrayList<>(List.of(row));
            } else if (published.equals(newestDate)) {
                newest.add(row);
            } else if (previousDate == null || published.isAfter(previousDate)) {
                previousDate = published;
                previous = new ArrayList<>(List.of(row));
            } else if (published.equals(previousDate)) {
                previous.add(row);
            }
        }

        List<DepthCharts.Spot> spots() {
            Map<String, Integer> before = new HashMap<>();
            previous.forEach(row -> before.put(row.gsisId(), row.rank()));
            return newest.stream()
                    .map(row -> new DepthCharts.Spot(row.gsisId(), row.player(), team,
                            row.position(), row.rank(), before.getOrDefault(row.gsisId(), 0),
                            newestDate))
                    .toList();
        }
    }
}
