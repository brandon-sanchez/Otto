package otto.habits;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Measures the user's habits from what the weekly capture holds. Every
 * habit is measured from his own results, and one that cost him nothing,
 * or has too few cases to tell, is left out.
 */
public final class Habits {

    /** Every habit Otto looks for, in the order the answer weighs them. */
    private static final List<HabitRule> RULES = List.of(
            LineupHabits::bench,
            AdviceHabits::overrides,
            LineupHabits::questionable,
            LineupHabits::chasing,
            LineupHabits::favouredTeam);

    private Habits() {
    }

    /**
     * Returns the habits his results show cost him points, and his
     * activity. Before {@link HabitReport#MINIMUM_WEEKS} scored weeks no
     * habit is measured at all.
     *
     * @param names a player's full name by Sleeper id
     */
    public static HabitReport measure(HabitHistory history, Function<String, String> names) {
        List<Integer> scored = history.scoredWeeks().stream().map(HabitWeek::week).toList();
        List<HabitReport.Habit> costly = scored.size() < HabitReport.MINIMUM_WEEKS
                ? List.of()
                : RULES.stream()
                        .map(rule -> rule.measure(history, names))
                        .flatMap(Optional::stream)
                        .toList();
        return new HabitReport(scored, costly, activity(history));
    }

    /**
     * How often he works the waiver wire, FAAB and trades, and at which
     * positions.
     */
    private static Map<String, String> activity(HabitHistory history) {
        List<HabitWeek.Move> moves = history.weeks().stream()
                .flatMap(week -> week.moves().stream())
                .toList();
        List<HabitWeek.Move> won = moves.stream().filter(HabitWeek.Move::won).toList();
        List<HabitWeek.Move> claims = won.stream()
                .filter(move -> "waiver".equals(move.type()) && !move.added().isEmpty())
                .toList();
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("waiverClaimsWon", String.valueOf(claims.size()));
        facts.put("waiverClaimsLost", String.valueOf(moves.size() - won.size()));
        facts.put("freeAgentAdds", String.valueOf(won.stream()
                .filter(move -> "free_agent".equals(move.type()) && !move.added().isEmpty())
                .count()));
        facts.put("trades", String.valueOf(won.stream()
                .filter(move -> "trade".equals(move.type())).count()));
        facts.put("drops", String.valueOf(won.stream()
                .filter(move -> !"trade".equals(move.type()))
                .mapToInt(move -> move.dropped().size()).sum()));
        if (moves.stream().anyMatch(move -> move.bid().isPresent())) {
            facts.put("faabSpent", "$" + claims.stream()
                    .mapToInt(move -> move.bid().orElse(0)).sum());
        }
        Map<String, Long> positions = new TreeMap<>(won.stream()
                .filter(move -> !"trade".equals(move.type()))
                .flatMap(move -> move.added().stream()
                        .map(playerId -> move.positions().get(playerId)))
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting())));
        if (!positions.isEmpty()) {
            facts.put("positionsAdded", positions.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .map(entry -> entry.getKey() + " " + entry.getValue())
                    .collect(Collectors.joining(", ")));
        }
        return facts;
    }
}
