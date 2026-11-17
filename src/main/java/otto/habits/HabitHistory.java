package otto.habits;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Everything the weekly capture holds for one league's season: each
 * week's lineup, designations and moves, and every call Otto made.
 */
public record HabitHistory(String season, List<HabitWeek> weeks, List<AdviceCall> calls) {

    public HabitHistory {
        weeks = weeks.stream().sorted(Comparator.comparingInt(HabitWeek::week)).toList();
        calls = List.copyOf(calls);
    }

    /** Returns the weeks Sleeper has scored, in week order. */
    public List<HabitWeek> scoredWeeks() {
        return weeks.stream().filter(week -> week.played().isPresent()).toList();
    }

    /** Returns one week, or empty when nothing was captured for it. */
    public Optional<HabitWeek> week(int number) {
        return weeks.stream().filter(week -> week.week() == number).findFirst();
    }
}
