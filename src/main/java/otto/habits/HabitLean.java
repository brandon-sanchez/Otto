package otto.habits;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How the user's costly habits tilt team analysis and trade ideas. Those
 * answers let it shape what they suggest and stress, and never name it:
 * the user hears about his habits only when he asks about them.
 *
 * <p>Only a habit his own results show cost him points tilts anything.
 *
 * @param depthAt the positions where his habits cost him points for want
 *        of a usable backup or a clear starter, costliest habit first;
 *        depth there is weighed as a need
 * @param steadyUsage true when chasing last week's big game cost him
 *        points, so players are pitched on their steady weekly role
 *        rather than on any one week
 */
public record HabitLean(List<String> depthAt, boolean steadyUsage) {

    /** The tilt of a user whose habits cost him nothing. */
    public static final HabitLean NONE = new HabitLean(List.of(), false);

    private static final Set<String> WEIGHED = Set.of("QB", "RB", "WR", "TE");

    private static final Set<String> NO_BACKUP_HABITS = Set.of("swaps", "byes");

    private static final double MIN_BENCH_SHARE_FOR_DEPTH = 0.5;

    public HabitLean {
        depthAt = List.copyOf(depthAt);
    }

    /**
     * Returns the tilt the costly habits in {@code report} give, reading
     * the positions they fell at from {@code history}.
     */
    public static HabitLean of(HabitHistory history, HabitReport report) {
        Map<String, String> positions = positions(history);
        Set<String> depth = new LinkedHashSet<>();
        boolean steady = false;
        for (HabitReport.Habit habit : report.costly()) {
            if ("bench".equals(habit.id())) {
                depth.addAll(benchHeavy(history));
            } else if (NO_BACKUP_HABITS.contains(habit.id())) {
                habit.players().stream()
                        .map(positions::get)
                        .filter(WEIGHED::contains)
                        .forEach(depth::add);
            } else if ("chasing".equals(habit.id())) {
                steady = true;
            }
        }
        return new HabitLean(List.copyOf(depth), steady);
    }

    /** Returns whether the habits leave the advice as it would otherwise be. */
    public boolean quiet() {
        return depthAt.isEmpty() && !steadyUsage;
    }

    private static List<String> benchHeavy(HabitHistory history) {
        Map<String, Double> left = new HashMap<>();
        for (HabitWeek week : history.scoredWeeks()) {
            PlayedWeek played = week.played().orElseThrow();
            for (String position : WEIGHED) {
                double gap = total(played, played.optimal(), position)
                        - total(played, played.starters(), position);
                if (gap > 0) {
                    left.merge(position, gap, Double::sum);
                }
            }
        }
        double all = left.values().stream().mapToDouble(Double::doubleValue).sum();
        return left.entrySet().stream()
                .filter(entry -> entry.getValue() >= MIN_BENCH_SHARE_FOR_DEPTH * all)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
    }

    private static double total(PlayedWeek played, List<String> lineup, String position) {
        return lineup.stream()
                .filter(playerId -> position.equals(played.positions().get(playerId)))
                .mapToDouble(playerId -> played.points().getOrDefault(playerId, 0.0))
                .sum();
    }

    private static Map<String, String> positions(HabitHistory history) {
        Map<String, String> positions = new HashMap<>();
        history.scoredWeeks().forEach(week ->
                positions.putAll(week.played().orElseThrow().positions()));
        return positions;
    }
}
