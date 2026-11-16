package otto.habits;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the user's own results say about how he manages: the habits that
 * cost him points, and a summary of his moves.
 *
 * @param scoredWeeks the week numbers the habits are measured over
 * @param costly every habit whose cost his results measure; a habit that
 *        cost nothing, or has too few cases to measure, is not here
 * @param activity his waiver, FAAB and trade activity, as facts that may
 *        be quoted
 */
public record HabitReport(List<Integer> scoredWeeks, List<Habit> costly,
        Map<String, String> activity) {

    /** The fewest scored weeks before any pattern is called a habit. */
    public static final int MINIMUM_WEEKS = 3;

    public HabitReport {
        scoredWeeks = List.copyOf(scoredWeeks);
        costly = List.copyOf(costly);
        activity = Map.copyOf(activity);
    }

    /** Returns whether there are enough scored weeks to measure any habit. */
    public boolean enoughWeeks() {
        return scoredWeeks.size() >= MINIMUM_WEEKS;
    }

    /**
     * One habit the user's results show cost him points.
     *
     * @param id names the habit, such as {@code bench} or {@code overrides}
     * @param facts the measured numbers and examples, written as they may
     *        be quoted, with {@code fix} holding one optional change
     * @param players the ids of the players the facts name
     */
    public record Habit(String id, Map<String, String> facts, List<String> players) {

        public Habit {
            facts = Map.copyOf(facts);
            players = List.copyOf(players);
        }
    }

    /** Writes points the way every habit fact quotes them, to one decimal. */
    static String points(double value) {
        return String.format(Locale.ROOT, "%.1f", value == 0 ? 0.0 : value);
    }
}
