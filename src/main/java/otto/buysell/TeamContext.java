package otto.buysell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import otto.nflverse.DepthCharts;
import otto.nflverse.Schedule;

/**
 * What changed around a player since last season, read from nflverse's
 * schedule, which names each game's starting quarterbacks and head
 * coaches, and its newest depth chart. A change of team, quarterback or
 * head coach makes last season a poor guide to this one; a backup
 * quarterback starting his team's latest game rules out a buy-low.
 *
 * @param changes what changed since last season, empty when nothing did
 *        or the schedule cannot say
 * @param backupQuarterback true when his team's latest game was started
 *        by a quarterback other than its QB1 on the newest depth chart;
 *        false when the chart names no QB1 for the team, because an
 *        unknown never counts against a player
 */
public record TeamContext(List<Change> changes, boolean backupQuarterback) {

    /** Nothing known to have changed, for a player the schedule cannot place. */
    public static final TeamContext UNKNOWN = new TeamContext(List.of(), false);

    /** One change since last season that resets last season's weight. */
    public enum Change {
        TEAM("he changed teams"),
        QUARTERBACK("his team's starting quarterback changed"),
        HEAD_COACH("his team's head coach changed");

        private final String words;

        Change(String words) {
            this.words = words;
        }

        /** Returns the change in plain words, such as "he changed teams". */
        public String words() {
            return words;
        }
    }

    public TeamContext {
        changes = List.copyOf(changes);
    }

    /**
     * Reads the context for one player.
     *
     * @param depthCharts the newest depth charts, empty when not stored
     * @param lastSeason his games last season, empty when he had none
     */
    public static TeamContext of(Schedule schedule, Optional<DepthCharts> depthCharts,
            String season, Optional<String> lastSeasonLabel, PlayerSeason thisSeason,
            Optional<PlayerSeason> lastSeason) {
        Optional<String> team = thisSeason.latestTeam();
        if (team.isEmpty()) {
            return UNKNOWN;
        }
        List<Schedule.Game> games = schedule.playedBy(season, team.get());
        Optional<String> starter = games.isEmpty() ? Optional.empty()
                : games.getFirst().quarterback(team.get());
        Optional<String> chartedStarter = depthCharts
                .flatMap(charts -> quarterbackOne(charts, team.get()));
        Optional<String> latestStarter = games.isEmpty() ? Optional.empty()
                : games.getLast().quarterback(team.get());
        boolean backup = differ(chartedStarter, latestStarter);
        List<Change> changes = new ArrayList<>();
        Optional<String> oldTeam = lastSeason.flatMap(PlayerSeason::latestTeam);
        if (oldTeam.isPresent() && lastSeasonLabel.isPresent()) {
            if (!oldTeam.get().equals(team.get())) {
                changes.add(Change.TEAM);
            }
            List<Schedule.Game> before = schedule.playedBy(lastSeasonLabel.get(), oldTeam.get());
            Optional<String> oldStarter = mostCommon(before,
                    game -> game.quarterback(oldTeam.get()));
            if (differ(oldStarter, starter)) {
                changes.add(Change.QUARTERBACK);
            }
            Optional<String> oldCoach = before.isEmpty() ? Optional.empty()
                    : before.getLast().coach(oldTeam.get());
            Optional<String> coach = games.isEmpty() ? Optional.empty()
                    : games.getLast().coach(team.get());
            if (differ(oldCoach, coach)) {
                changes.add(Change.HEAD_COACH);
            }
        }
        return new TeamContext(changes, backup);
    }

    private static Optional<String> quarterbackOne(DepthCharts charts, String team) {
        return charts.rows().stream()
                .filter(spot -> spot.team().equals(team) && spot.position().equals("QB")
                        && spot.rank() == 1)
                .map(DepthCharts.Spot::gsisId)
                .findFirst();
    }

    private static boolean differ(Optional<String> before, Optional<String> now) {
        return before.isPresent() && now.isPresent() && !before.equals(now);
    }

    private static Optional<String> mostCommon(List<Schedule.Game> games,
            Function<Schedule.Game, Optional<String>> named) {
        Map<String, Long> counts = games.stream()
                .flatMap(game -> named.apply(game).stream())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        return counts.entrySet().stream()
                .max(Map.Entry.<String, Long>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .map(Map.Entry::getKey);
    }
}
