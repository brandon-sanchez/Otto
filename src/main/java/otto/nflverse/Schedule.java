package otto.nflverse;

import java.time.Instant;
import java.util.List;

/**
 * The regular-season schedule for the current season and the one before
 * it, from {@code schedules/games.csv}. It is the one source of what
 * should exist in a week: a feed holds a week when it holds every unit
 * the schedule says that week has.
 *
 * The previous season rides along because the stats and snap feeds read
 * last season's final file until a current-season week has been played.
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
        List<Game> rows) implements NflverseFeed<Schedule.Game> {

    /**
     * One regular-season game, team codes in Sleeper's vocabulary.
     *
     * @param finished true once nflverse has written a result
     */
    public record Game(String gameId, String season, int week, Instant kickoff, String home,
            String away, boolean finished) {
    }
}
