package otto.nflverse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static otto.nflverse.FeedRows.KEPT_POSITIONS;
import static otto.nflverse.FeedRows.blankOrNa;
import static otto.nflverse.FeedRows.optionalNumber;
import static otto.nflverse.FeedRows.requireColumns;

/**
 * ffverse's ffopportunity {@code ep_weekly} file, one season at a time.
 * It is published from its own repository's releases, beside nflverse's.
 */
final class ExpectedPointsFeed implements FeedSpec<ExpectedPoints.Line, ExpectedPoints> {

    /**
     * The last regular-season week. The file has no season-type column
     * and carries the playoffs as weeks 19 onwards.
     */
    private static final int LAST_REGULAR_WEEK = 18;

    private static final Set<String> COLUMNS = Set.of(
            "player_id", "position", "posteam", "week", "game_id", "total_touchdown",
            "total_touchdown_exp", "total_fantasy_points", "total_fantasy_points_exp");

    @Override
    public FeedId id() {
        return FeedId.EXPECTED_POINTS;
    }

    @Override
    public String documentName() {
        return "ffopportunity-expected-points";
    }

    @Override
    public String repo() {
        return "ffverse/ffopportunity";
    }

    @Override
    public String tag() {
        return "latest-data";
    }

    @Override
    public String asset(String season) {
        return "ep_weekly_%s.csv".formatted(season);
    }

    @Override
    public SeasonRule seasonRule() {
        return SeasonRule.LAST_PLAYED;
    }

    @Override
    public Grain<ExpectedPoints.Line> grain() {
        return new Grain.PerTeamWeek<>(ExpectedPoints.Line::week, ExpectedPoints.Line::team);
    }

    @Override
    public List<ExpectedPoints.Line> read(Basis basis, Stream<Csv.Row> rows) {
        List<ExpectedPoints.Line> lines = new ArrayList<>();
        rows.forEach(row -> {
            requireColumns(row, COLUMNS);
            String gsisId = row.text("player_id");
            int week = row.integer("week");
            if (blankOrNa(gsisId) || !KEPT_POSITIONS.contains(row.text("position"))
                    || week < 1 || week > LAST_REGULAR_WEEK) {
                return;
            }
            lines.add(new ExpectedPoints.Line(gsisId, row.text("position"),
                    NflTeams.normalize(row.text("posteam")), week, row.text("game_id"),
                    optionalNumber(row, "total_touchdown"),
                    optionalNumber(row, "total_touchdown_exp"),
                    optionalNumber(row, "total_fantasy_points"),
                    optionalNumber(row, "total_fantasy_points_exp")));
        });
        return lines;
    }

    @Override
    public ExpectedPoints document(Basis basis, Instant assetUpdatedAt, Instant checkedAt,
            List<ExpectedPoints.Line> rows, Coverage coverage) {
        return new ExpectedPoints(basis.season(), basis.priorSeasonFinal(), assetUpdatedAt,
                checkedAt, coverage, rows);
    }

    @Override
    public ExpectedPoints recheck(ExpectedPoints current, Basis basis, Instant checkedAt) {
        return new ExpectedPoints(basis.season(), basis.priorSeasonFinal(),
                current.assetUpdatedAt(), checkedAt, current.coverage(), current.rows());
    }

    @Override
    public Class<ExpectedPoints> type() {
        return ExpectedPoints.class;
    }
}
