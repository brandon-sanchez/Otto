package otto.habits;

import java.util.Optional;
import java.util.function.Function;

/** One habit Otto looks for in the user's own results. */
interface HabitRule {

    /**
     * Returns the habit when the user's results show it cost him points
     * over enough cases to measure, and empty otherwise.
     *
     * @param names a player's full name by Sleeper id, or the id when Otto
     *        does not know the name
     */
    Optional<HabitReport.Habit> measure(HabitHistory history, Function<String, String> names);
}
