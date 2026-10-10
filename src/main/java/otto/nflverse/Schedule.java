package otto.nflverse;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.stream.Collectors;

/**
 * The regular-season schedule for the current season and the one before
 * it, from {@code schedules/games.csv}. It is the one source of what
 * should exist in a week: a feed holds a week when it holds every unit
 * the schedule says that week has.
 *
 * The previous season rides along because the stats and snap feeds read
 * last season's final file until a current-season game is final.
 *
 * @param season the season Sleeper published when this copy was taken
 * @param assetUpdatedAt the release timestamp this copy was taken at
 * @param checkedAt when the hourly timestamp check last ran
 * @param coverage which units the rows hold and when each last changed
 */
public record Schedule(
        String season,
        Instant assetUpdatedAt,
        Instant checkedAt,
        Coverage coverage,
        List<Game> rows) implements NflverseFeed {

    /**
     * One regular-season game, team codes in Sleeper's vocabulary.
     *
     * @param finished true once nflverse has written a result
     */
    public record Game(String gameId, String season, int week, Instant kickoff, String home,
            String away, boolean finished) {
    }

    record Window(Instant start, Instant end) {
    }

    /** One week's games in kickoff order. */
    List<Game> games(String season, int week) {
        return rows.stream()
                .filter(game -> game.season().equals(season) && game.week() == week)
                .sorted(Comparator.comparing(Game::kickoff).thenComparing(Game::gameId))
                .toList();
    }

    boolean anyFinal(String season) {
        return rows.stream().anyMatch(game -> game.season().equals(season) && game.finished());
    }

    /** The newest week every listed game of which is final; empty before week 1 is done. */
    OptionalInt latestCompletedWeek(String season) {
        Map<Integer, Boolean> allFinal = rows.stream()
                .filter(game -> game.season().equals(season))
                .collect(Collectors.toMap(Game::week, Game::finished, Boolean::logicalAnd));
        return allFinal.entrySet().stream()
                .filter(Map.Entry::getValue)
                .mapToInt(Map.Entry::getKey)
                .max();
    }

    /**
     * The moment from which an undated snapshot, such as a depth chart,
     * counts for a week: the start of the day after the previous week's
     * last game day, or a week before the first kickoff when the schedule
     * lists no week before it. A chart dated after that is the one teams
     * set for this week's games.
     *
     * @param week a week the schedule lists games for, or the one after the last listed
     */
    Instant windowStart(String season, int week) {
        List<Game> previous = games(season, week - 1);
        if (previous.isEmpty()) {
            return games(season, week).getFirst().kickoff().minus(Duration.ofDays(7));
        }
        LocalDate lastGameDay = previous.getLast().kickoff()
                .atZone(ScheduleFeed.EASTERN).toLocalDate();
        return lastGameDay.plusDays(1).atStartOfDay(ScheduleFeed.EASTERN).toInstant();
    }

    Window window(String season, int week) {
        return new Window(windowStart(season, week), windowStart(season, week + 1));
    }
}
