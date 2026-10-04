package otto.nflverse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static otto.nflverse.FeedRows.KEPT_POSITIONS;
import static otto.nflverse.FeedRows.REGULAR_SEASON;
import static otto.nflverse.FeedRows.blankOrNa;
import static otto.nflverse.FeedRows.requireColumns;

/**
 * The week-level roster standings, which carry the one fact no other
 * feed here does: whether an absence has a date the player comes back
 * on. The waiver breakout tag reads it, so that an IR spell designated
 * for return is priced as the loan it is.
 */
final class WeeklyRostersFeed implements FeedSpec<WeeklyRosters.Standing, WeeklyRosters> {

    private static final Set<String> COLUMNS = Set.of(
            "gsis_id", "team", "week", "position", "game_type", "status_description_abbr");

    @Override
    public FeedId id() {
        return FeedId.WEEKLY_ROSTERS;
    }

    @Override
    public String tag() {
        return "weekly_rosters";
    }

    @Override
    public String asset(String season) {
        return "roster_weekly_%s.csv".formatted(season);
    }

    @Override
    public SeasonRule seasonRule() {
        return SeasonRule.CURRENT;
    }

    /**
     * Only the standing itself is kept, and only for the four
     * positions the Player Directory keeps and the regular season this
     * league plays. Everything else in this file - names, ids,
     * colleges, draft position, and the whole defensive and offensive
     * line - is either already in the Player Directory and the id map,
     * where a second copy would drift from the first, or is nothing
     * any waiver question can ask about.
     */
    @Override
    public List<WeeklyRosters.Standing> read(Basis basis, Stream<Csv.Row> rows) {
        List<WeeklyRosters.Standing> standings = new ArrayList<>();
        rows.forEach(row -> {
            requireColumns(row, COLUMNS);
            String gsisId = row.text("gsis_id");
            String code = row.text("status_description_abbr");
            if (blankOrNa(gsisId) || blankOrNa(code)
                    || !KEPT_POSITIONS.contains(row.text("position"))
                    || !REGULAR_SEASON.equals(row.text("game_type"))) {
                return;
            }
            standings.add(new WeeklyRosters.Standing(gsisId, row.integer("week"), code,
                    NflTeams.normalize(row.text("team"))));
        });
        return standings;
    }

    @Override
    public Coverage.Unit unit(WeeklyRosters.Standing standing) {
        return new Coverage.Unit(standing.week(), standing.team());
    }

    @Override
    public WeeklyRosters document(Basis basis, Instant assetUpdatedAt, Instant checkedAt,
            List<WeeklyRosters.Standing> rows, Coverage coverage) {
        return new WeeklyRosters(basis.season(), assetUpdatedAt, checkedAt, coverage, rows);
    }

    @Override
    public Class<WeeklyRosters> type() {
        return WeeklyRosters.class;
    }
}
