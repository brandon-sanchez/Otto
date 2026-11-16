package otto.habits;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything the weekly capture holds for one league's season: each
 * week's lineup, designations and moves, every call Otto made, and the
 * league's draft.
 *
 * @param drafted where each player went in the league's draft, by player
 *        id; a player nobody drafted is absent
 */
public record HabitHistory(String season, List<HabitWeek> weeks, List<AdviceCall> calls,
        Map<String, Drafted> drafted) {

    public HabitHistory {
        weeks = weeks.stream().sorted(Comparator.comparingInt(HabitWeek::week)).toList();
        calls = List.copyOf(calls);
        drafted = drafted == null ? Map.of() : Map.copyOf(drafted);
    }

    /**
     * Where one player went in the league's draft.
     *
     * @param pickNumber his place in the whole draft, from 1
     * @param byUser true when the user's team picked him
     */
    public record Drafted(int round, int pickNumber, boolean byUser) {
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
