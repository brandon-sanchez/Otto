package otto.matchup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import otto.nflverse.DefenseVersusPosition;
import otto.nflverse.Schedule;

/**
 * How hard the rest of each team's regular season is for each position:
 * the points per game, in this league's scoring, that the team's
 * remaining opponents allow to that position. Ranking the teams on it is
 * left to the league rankings, so a remaining schedule ranks by the same
 * rules as every other ranked stat.
 *
 * @param season the season whose remaining games are rated
 * @param basis what the opponents' points allowed describe, copied from
 *        {@link DefenseVersusPosition#basis()}
 * @param firstWeek the earliest week with a remaining game, or 0 when
 *        no game is left
 * @param lastWeek the latest week with a remaining game, or 0 when no
 *        game is left
 * @param byTeam each team's ratings keyed by position; a team none of
 *        whose remaining opponents is in the defense table is absent
 */
public record ScheduleStrength(
        String season,
        String basis,
        int firstWeek,
        int lastWeek,
        Map<String, Map<String, Rating>> byTeam) {

    /**
     * One team's remaining schedule for one position.
     *
     * @param perGame the average points per game the remaining opponents
     *        allow to the position
     * @param games how many remaining games the average is over; byes
     *        and games already played are not counted
     */
    public record Rating(double perGame, int games) {
    }

    /** Returns the team's rating at the position, or empty when it has none. */
    public Optional<Rating> of(String team, String position) {
        return Optional.ofNullable(byTeam.get(team)).map(byPosition -> byPosition.get(position));
    }

    /**
     * Rates every team's unplayed regular-season games of the schedule's
     * own season against the defense table. A game counts as played once
     * nflverse records its result, so a team that played on Thursday has
     * that game behind it while the rest of the week is still ahead.
     * A remaining opponent missing from the table is left out of the
     * average rather than counted as allowing nothing.
     */
    public static ScheduleStrength rate(Schedule schedule, DefenseVersusPosition defenses) {
        Map<String, List<String>> remainingOpponents = new HashMap<>();
        IntSummaryStatistics weeks = new IntSummaryStatistics();
        schedule.rows().stream()
                .filter(game -> game.season().equals(schedule.season()) && !game.finished())
                .forEach(game -> {
                    weeks.accept(game.week());
                    remainingOpponents.computeIfAbsent(game.home(), team -> new ArrayList<>())
                            .add(game.away());
                    remainingOpponents.computeIfAbsent(game.away(), team -> new ArrayList<>())
                            .add(game.home());
                });

        Map<String, Map<String, List<Double>>> allowedByTeam = new HashMap<>();
        remainingOpponents.forEach((team, opponents) -> opponents.stream()
                .map(defenses.byTeam()::get)
                .filter(Objects::nonNull)
                .forEach(defense -> defense.byPosition().forEach((position, allowed) ->
                        allowedByTeam
                                .computeIfAbsent(team, key -> new HashMap<>())
                                .computeIfAbsent(position, key -> new ArrayList<>())
                                .add(allowed.perGame()))));

        Map<String, Map<String, Rating>> byTeam = new HashMap<>();
        allowedByTeam.forEach((team, byPosition) -> {
            Map<String, Rating> ratings = new HashMap<>();
            byPosition.forEach((position, allowed) -> ratings.put(position, new Rating(
                    allowed.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),
                    allowed.size())));
            byTeam.put(team, Map.copyOf(ratings));
        });
        boolean anyLeft = weeks.getCount() > 0;
        return new ScheduleStrength(schedule.season(), defenses.basis(),
                anyLeft ? weeks.getMin() : 0, anyLeft ? weeks.getMax() : 0, Map.copyOf(byTeam));
    }
}
