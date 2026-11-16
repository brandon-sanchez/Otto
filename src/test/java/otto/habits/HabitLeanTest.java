package otto.habits;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HabitLeanTest {

    private static final List<String> SLOTS = List.of("QB", "RB", "WR", "FLEX");

    private static final List<String> STARTERS = List.of("qb", "rb1", "wr1", "wr2");

    private static final Map<String, String> POSITIONS = Map.of("qb", "QB", "rb1", "RB",
            "wr1", "WR", "wr2", "WR", "rb-bench", "RB", "wr-bench", "WR");

    @Test
    void runningBackPointsLeftOnTheBenchWeighRunningBackDepth() {
        HabitHistory history = history(16.0);

        HabitLean lean = HabitLean.of(history, Habits.measure(history, Function.identity()));

        assertThat(lean.depthAt()).containsExactly("RB");
        assertThat(lean.steadyUsage()).isFalse();
    }

    @Test
    void aLineupThatCostNothingLeavesTheAdviceAsItIs() {
        HabitHistory history = history(2.0);

        HabitLean lean = HabitLean.of(history, Habits.measure(history, Function.identity()));

        assertThat(lean).isEqualTo(HabitLean.NONE);
        assertThat(lean.quiet()).isTrue();
    }

    @Test
    void chasingLastWeeksBigGameFramesTheAdviceAroundSteadyUsage() {
        HabitReport chased = report(new HabitReport.Habit("chasing",
                Map.of("pointsLost", "21.0"), List.of("wr2")));

        HabitLean lean = HabitLean.of(history(2.0), chased);

        assertThat(lean.steadyUsage()).isTrue();
        assertThat(lean.depthAt()).isEmpty();
        assertThat(lean.quiet()).isFalse();
    }

    @Test
    void startersLostToInjuriesAndByesWeighDepthAtTheirPositions() {
        HabitReport report = report(
                new HabitReport.Habit("swaps", Map.of("pointsLost", "30.0"), List.of("wr1")),
                new HabitReport.Habit("byes", Map.of("pointsBelowUsual", "12.0"),
                        List.of("qb", "rb1")));

        HabitLean lean = HabitLean.of(history(2.0), report);

        assertThat(lean.depthAt()).containsExactly("WR", "QB", "RB");
    }

    private static HabitReport report(HabitReport.Habit... costly) {
        return new HabitReport(List.of(1, 2, 3), List.of(costly), Map.of());
    }

    private static HabitHistory history(double benchedBack) {
        List<HabitWeek> weeks = IntStream.rangeClosed(1, 3)
                .mapToObj(week -> new HabitWeek(week, Optional.of(played(benchedBack)),
                        Map.of(), List.of(), Set.of(), Set.of(), List.of()))
                .toList();
        return new HabitHistory("2026", weeks, List.of(), Map.of());
    }

    private static PlayedWeek played(double benchedBack) {
        Map<String, Double> points = new HashMap<>(Map.of("qb", 20.0, "rb1", 8.0, "wr1", 10.0,
                "wr2", 9.0, "wr-bench", 3.0, "rb-bench", benchedBack));
        List<String> optimal = List.of("qb", benchedBack > 8.0 ? "rb-bench" : "rb1", "wr1",
                "wr2");
        return new PlayedWeek(SLOTS, STARTERS, points, Map.of(), POSITIONS, Map.of(), optimal,
                Set.of(), Map.of(), Map.of());
    }
}
