package otto.nflverse;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static otto.nflverse.FeedRows.REGULAR_SEASON;
import static otto.nflverse.FeedRows.blankOrNa;
import static otto.nflverse.FeedRows.requireColumns;

/**
 * Every season's games in one file, republished daily. Only the regular
 * season of the current season and the one before it is kept, and the
 * rest is dropped as the file streams past.
 */
final class ScheduleFeed implements FeedSpec<Schedule.Game, Schedule> {

    /** nflverse writes game days and kickoffs as Eastern local time. */
    static final ZoneId EASTERN = ZoneId.of("America/New_York");

    private static final Pattern SEASON = Pattern.compile("\\d{4}");

    private static final Set<String> COLUMNS = Set.of(
            "game_id", "season", "game_type", "week", "gameday", "gametime",
            "away_team", "home_team", "result");

    @Override
    public FeedId id() {
        return FeedId.SCHEDULE;
    }

    @Override
    public String tag() {
        return "schedules";
    }

    @Override
    public String asset(String season) {
        return "games.csv";
    }

    @Override
    public SeasonRule seasonRule() {
        return SeasonRule.CURRENT;
    }

    @Override
    public List<Schedule.Game> read(Basis basis, Stream<Csv.Row> rows) {
        Set<String> kept = SEASON.matcher(basis.season()).matches()
                ? Set.of(basis.season(), String.valueOf(Integer.parseInt(basis.season()) - 1))
                : Set.of(basis.season());
        List<Schedule.Game> games = new ArrayList<>();
        rows.forEach(row -> {
            requireColumns(row, COLUMNS);
            if (!kept.contains(row.text("season"))
                    || !REGULAR_SEASON.equals(row.text("game_type"))) {
                return;
            }
            games.add(new Schedule.Game(
                    row.text("game_id"),
                    row.text("season"),
                    row.integer("week"),
                    kickoff(row.text("gameday"), row.text("gametime")),
                    NflTeams.normalize(row.text("home_team")),
                    NflTeams.normalize(row.text("away_team")),
                    !blankOrNa(row.text("result"))));
        });
        return games;
    }

    /**
     * A game day this code cannot read is drift: the week's window for
     * the depth charts is placed from it, so a guess would move the
     * line between one week's chart and the next.
     */
    private static Instant kickoff(String gameday, String gametime) {
        try {
            LocalTime time = blankOrNa(gametime) ? LocalTime.MIDNIGHT : LocalTime.parse(gametime);
            return LocalDate.parse(gameday).atTime(time).atZone(EASTERN).toInstant();
        } catch (DateTimeParseException unreadable) {
            throw new IllegalStateException("schema drift: kickoff \"%s %s\" is not a date and time"
                    .formatted(gameday, gametime));
        }
    }

    @Override
    public Coverage.Unit unit(Schedule.Game game) {
        return new Coverage.Unit(game.week(), game.gameId());
    }

    /** A result arriving is not a schedule correction; a moved kickoff is. */
    @Override
    public Object content(Schedule.Game game) {
        return List.of(game.gameId(), game.week(), game.kickoff(), game.home(), game.away());
    }

    @Override
    public Schedule document(Basis basis, Instant assetUpdatedAt, Instant checkedAt,
            List<Schedule.Game> rows, Coverage coverage) {
        return new Schedule(basis.season(), assetUpdatedAt, checkedAt, coverage, rows);
    }

    @Override
    public Class<Schedule> type() {
        return Schedule.class;
    }
}
