package otto.nflverse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * How hard the rest of each team's regular season is for each position:
 * the points per game, in this league's scoring, that the team's
 * remaining opponents allow to that position.
 *
 * Rank 1 is the softest schedule, the one whose opponents allow the
 * most, so a high rank reads as a hard road ahead. That runs the other
 * way from {@link DefenseVersusPosition}, where rank 1 is the toughest
 * single defense, so a reason string must say which way a rank runs.
 *
 * @param season the season whose remaining games are rated
 * @param basis what the opponents' points allowed describe, copied from
 *        {@link DefenseVersusPosition#basis()}
 * @param byTeam each team's ratings keyed by position; a team none of
 *        whose remaining opponents is in the defense table is absent
 */
public record ScheduleStrength(
        String season,
        String basis,
        Map<String, Map<String, Rating>> byTeam) {

    /**
     * One team's remaining schedule for one position.
     *
     * @param perGame the average points per game the remaining opponents
     *        allow to the position
     * @param games how many remaining games the average is over; byes
     *        and games already played are not counted
     * @param rank 1 is the softest schedule among {@code teams}
     * @param teams how many teams the rank runs over
     */
    public record Rating(double perGame, int games, int rank, int teams) {
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
    static ScheduleStrength rate(Schedule schedule, DefenseVersusPosition defenses) {
        Map<String, List<String>> remainingOpponents = new HashMap<>();
        schedule.rows().stream()
                .filter(game -> game.season().equals(schedule.season()) && !game.finished())
                .forEach(game -> {
                    remainingOpponents.computeIfAbsent(game.home(), team -> new ArrayList<>())
                            .add(game.away());
                    remainingOpponents.computeIfAbsent(game.away(), team -> new ArrayList<>())
                            .add(game.home());
                });

        Map<String, Map<String, List<Double>>> allowedByPosition = new HashMap<>();
        remainingOpponents.forEach((team, opponents) -> opponents.stream()
                .map(defenses.byTeam()::get)
                .filter(Objects::nonNull)
                .forEach(defense -> defense.byPosition().forEach((position, allowed) ->
                        allowedByPosition
                                .computeIfAbsent(position, key -> new HashMap<>())
                                .computeIfAbsent(team, key -> new ArrayList<>())
                                .add(allowed.perGame()))));

        Map<String, Map<String, Rating>> byTeam = new HashMap<>();
        allowedByPosition.forEach((position, allowedByTeam) -> {
            Map<String, Double> averages = new HashMap<>();
            allowedByTeam.forEach((team, allowed) -> averages.put(team,
                    allowed.stream().mapToDouble(Double::doubleValue).average().orElseThrow()));
            List<String> softestFirst = softestFirst(averages);
            for (int index = 0; index < softestFirst.size(); index++) {
                String team = softestFirst.get(index);
                byTeam.computeIfAbsent(team, key -> new HashMap<>()).put(position, new Rating(
                        averages.get(team), allowedByTeam.get(team).size(), index + 1,
                        softestFirst.size()));
            }
        });
        return new ScheduleStrength(schedule.season(), defenses.basis(), byTeam);
    }

    /**
     * Ties break on the team code so that a rebuild from the same inputs
     * never reshuffles two equal schedules.
     */
    private static List<String> softestFirst(Map<String, Double> averages) {
        List<String> order = new ArrayList<>(averages.keySet());
        order.sort(Comparator.comparingDouble((String team) -> averages.get(team))
                .reversed()
                .thenComparing(Comparator.naturalOrder()));
        return order;
    }
}
