package otto.board;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import otto.nflverse.Coverage;
import otto.nflverse.FeedId;
import otto.nflverse.NflverseStore;
import otto.nflverse.Schedule;
import otto.nflverse.WeekStatus;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;

/**
 * Works out which of the waiver board's late nflverse feeds Sleeper's
 * stats endpoint can fill for the latest completed week. A feed is
 * filled only in full: Sleeper must hold the game of every team the
 * feed lacks, and for the snap counts its lines must count snaps.
 */
@Component
class SleeperBackup {

    private final NflverseStore nflverse;
    private final SleeperAdapter sleeper;

    SleeperBackup(NflverseStore nflverse, SleeperAdapter sleeper) {
        this.nflverse = nflverse;
        this.sleeper = sleeper;
    }

    /**
     * Returns what Sleeper fills of the feeds that lack part of the week.
     *
     * <p>Side effects: reads the stored schedule and makes one Sleeper
     * stats request.
     *
     * @param lacking each late feed and the units it lacks
     * @return unavailable when Sleeper's stats cannot be read; otherwise
     *         the fill, which fills no feed when Sleeper holds too little
     */
    SourceResult<SleeperFill> fill(String season, int week,
            Map<FeedId, WeekStatus.Incomplete> lacking) {
        List<Schedule.Game> games = nflverse.schedule().map(Schedule::rows).orElse(List.of())
                .stream()
                .filter(game -> game.season().equals(season) && game.week() == week)
                .toList();
        return sleeper.playedWeek(season, week).flatMap(stats -> {
            Map<FeedId, Set<String>> teamsByFeed = new EnumMap<>(FeedId.class);
            lacking.forEach((feed, incomplete) -> teams(incomplete, games)
                    .filter(teams -> teams.stream().allMatch(stats::holdsGame))
                    .filter(teams -> feed != FeedId.SNAP_COUNTS || stats.countsSnaps())
                    .ifPresent(teams -> teamsByFeed.put(feed, teams)));
            return new SourceResult.Ok<>(teamsByFeed.isEmpty() ? SleeperFill.NONE
                    : new SleeperFill(week, teamsByFeed, opponents(games), stats));
        });
    }

    private static Map<String, String> opponents(List<Schedule.Game> games) {
        Map<String, String> opponents = new HashMap<>();
        games.forEach(game -> {
            opponents.put(game.home(), game.away());
            opponents.put(game.away(), game.home());
        });
        return opponents;
    }

    /**
     * Returns the teams whose games the feed lacks, empty when a missing
     * unit names no team the schedule can place.
     */
    private static Optional<Set<String>> teams(WeekStatus.Incomplete incomplete,
            List<Schedule.Game> games) {
        Map<String, Schedule.Game> byId = games.stream()
                .collect(Collectors.toMap(Schedule.Game::gameId, Function.identity()));
        Set<String> teams = new LinkedHashSet<>();
        for (Coverage.Unit unit : incomplete.missing()) {
            switch (unit) {
                case Coverage.Unit.Game game -> {
                    Schedule.Game scheduled = byId.get(game.gameId());
                    if (scheduled == null) {
                        return Optional.empty();
                    }
                    teams.add(scheduled.away());
                    teams.add(scheduled.home());
                }
                case Coverage.Unit.TeamWeek teamWeek -> teams.add(teamWeek.team());
                case Coverage.Unit.Team team -> teams.add(team.team());
                case Coverage.Unit.Week _ -> games.forEach(game -> {
                    teams.add(game.away());
                    teams.add(game.home());
                });
                case Coverage.Unit.File _ -> {
                    return Optional.empty();
                }
            }
        }
        return teams.isEmpty() ? Optional.empty() : Optional.of(teams);
    }
}
