package otto.nflverse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static otto.nflverse.FeedRows.REGULAR_SEASON;
import static otto.nflverse.FeedRows.blankOrNa;
import static otto.nflverse.FeedRows.requireColumns;

/**
 * PFR's weekly rushing advanced stats as nflverse republishes them. Like
 * the snap counts, the season's file appears only once it has a played
 * week, so it follows the last-played season.
 */
final class RushingAdvancedStatsFeed
        implements FeedSpec<RushingAdvancedStats.RushLine, RushingAdvancedStats> {

    private static final Set<String> COLUMNS = Set.of(
            "game_id", "week", "game_type", "team", "opponent", "pfr_player_name",
            "pfr_player_id", "carries", "rushing_yards_before_contact",
            "rushing_yards_after_contact", "rushing_broken_tackles");

    @Override
    public FeedId id() {
        return FeedId.RUSHING_ADVANCED_STATS;
    }

    @Override
    public String documentName() {
        return "nflverse-advstats-rush";
    }

    @Override
    public String tag() {
        return "pfr_advstats";
    }

    @Override
    public String asset(String season) {
        return "advstats_week_rush_%s.csv".formatted(season);
    }

    @Override
    public SeasonRule seasonRule() {
        return SeasonRule.LAST_PLAYED;
    }

    @Override
    public Grain<RushingAdvancedStats.RushLine> grain() {
        return new Grain.PerGame<>(RushingAdvancedStats.RushLine::week,
                RushingAdvancedStats.RushLine::gameId);
    }

    @Override
    public List<RushingAdvancedStats.RushLine> read(Basis basis, Stream<Csv.Row> rows) {
        List<RushingAdvancedStats.RushLine> lines = new ArrayList<>();
        rows.forEach(row -> {
            requireColumns(row, COLUMNS);
            String gameId = row.text("game_id");
            if (blankOrNa(gameId) || !REGULAR_SEASON.equals(row.text("game_type"))) {
                return;
            }
            lines.add(new RushingAdvancedStats.RushLine(
                    row.text("pfr_player_id"),
                    row.text("pfr_player_name"),
                    gameId,
                    row.integer("week"),
                    NflTeams.normalize(row.text("team")),
                    NflTeams.normalize(row.text("opponent")),
                    row.integer("carries"),
                    row.integer("rushing_yards_before_contact"),
                    row.integer("rushing_yards_after_contact"),
                    row.integer("rushing_broken_tackles")));
        });
        return lines;
    }

    @Override
    public RushingAdvancedStats document(Basis basis, Instant assetUpdatedAt, Instant checkedAt,
            List<RushingAdvancedStats.RushLine> rows, Coverage coverage) {
        return new RushingAdvancedStats(basis.season(), basis.priorSeasonFinal(), assetUpdatedAt,
                checkedAt, coverage, rows);
    }

    @Override
    public RushingAdvancedStats recheck(RushingAdvancedStats current, Basis basis,
            Instant checkedAt) {
        return new RushingAdvancedStats(basis.season(), basis.priorSeasonFinal(),
                current.assetUpdatedAt(), checkedAt, current.coverage(), current.rows());
    }

    @Override
    public Class<RushingAdvancedStats> type() {
        return RushingAdvancedStats.class;
    }
}
