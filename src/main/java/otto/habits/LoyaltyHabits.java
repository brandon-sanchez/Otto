package otto.habits;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The habits of sticking with a player for who he is rather than how he
 * is playing: keeping his own early picks and big names in close calls,
 * and holding one kicker or defense all season.
 */
final class LoyaltyHabits {

    /** Fewest contested start/sit calls before loyalty is measured. */
    static final int MINIMUM_CONTESTED_CALLS = 8;

    /** The latest draft round whose picks count as the user's own early picks. */
    static final int EARLY_ROUNDS = 4;

    /** Fewest weeks starting the same K or DEF before holding him is measured. */
    static final int MINIMUM_HELD_WEEKS = 6;

    /** The free agent must have outscored the held K or DEF by this much a week. */
    static final double STREAMING_GAP_PER_WEEK = 2.0;

    private LoyaltyHabits() {
    }

    /**
     * Keeping one of his own early picks, or the player the league drafted
     * earlier, when Otto said to start someone else. Measured by what
     * those kept players gave up to Otto's pick, and reported only when
     * he overrides these calls more often than his other calls.
     */
    static Optional<HabitReport.Habit> loyalty(HabitHistory history,
            Function<String, String> names) {
        Map<Boolean, List<AdviceHabits.Judged>> split = history.calls().stream()
                .filter(call -> call.kind() == AdviceCall.Kind.START)
                .flatMap(call -> AdviceHabits.judge(history, call).stream())
                .collect(Collectors.partitioningBy(call -> contested(history, call.call())));
        List<AdviceHabits.Judged> contested = split.get(true);
        List<AdviceHabits.Judged> other = split.get(false);
        List<AdviceHabits.Judged> kept = contested.stream().filter(call -> !call.followed())
                .toList();
        double lost = kept.stream().mapToDouble(AdviceHabits.Judged::margin).sum();
        if (contested.size() < MINIMUM_CONTESTED_CALLS || lost <= 0
                || overrideShare(contested) <= overrideShare(other)) {
            return Optional.empty();
        }
        AdviceHabits.Judged costliest = kept.stream()
                .max(Comparator.comparingDouble(AdviceHabits.Judged::margin)).orElseThrow();
        String start = costliest.call().playerId();
        String sit = costliest.call().over().orElseThrow();
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("contestedCalls", String.valueOf(contested.size()));
        facts.put("keptTheBiggerName", String.valueOf(kept.size()));
        facts.put("pointsLost", HabitReport.points(lost));
        if (!other.isEmpty()) {
            facts.put("otherCalls", String.valueOf(other.size()));
            facts.put("otherCallsOverridden", String.valueOf(other.stream()
                    .filter(call -> !call.followed()).count()));
        }
        facts.put("contestedMeans", "Otto said to bench one of your first %d draft picks, or a "
                .formatted(EARLY_ROUNDS) + "player drafted earlier than the one to start");
        facts.put("example", ("In week %d Otto said to start %s over %s; you kept %s, who "
                + "scored %s to %s's %s.").formatted(costliest.call().week(), names.apply(start),
                names.apply(sit), names.apply(sit), HabitReport.points(costliest.sat()),
                names.apply(start), HabitReport.points(costliest.started())));
        facts.put("fix", "In a close call, judge your early picks and big names on this week's "
                + "role and matchup, the same way you'd judge a waiver add.");
        return Optional.of(new HabitReport.Habit("loyalty", facts, List.of(start, sit)));
    }

    /**
     * Whether the player Otto said to sit is one of the user's own early
     * picks, or went earlier in the league's draft than Otto's pick.
     */
    private static boolean contested(HabitHistory history, AdviceCall call) {
        Optional<HabitHistory.Drafted> sit = Optional.ofNullable(
                history.drafted().get(call.over().orElseThrow()));
        if (sit.isEmpty()) {
            return false;
        }
        if (sit.get().byUser() && sit.get().round() <= EARLY_ROUNDS) {
            return true;
        }
        return Optional.ofNullable(history.drafted().get(call.playerId()))
                .map(start -> sit.get().pickNumber() < start.pickNumber())
                .orElse(true);
    }

    private static double overrideShare(List<AdviceHabits.Judged> calls) {
        return calls.isEmpty() ? 0
                : (double) calls.stream().filter(call -> !call.followed()).count() / calls.size();
    }

    /**
     * Starting the same kicker or defense week after week, measured
     * against the free agent projected highest at the position each of
     * those weeks. The position with the bigger gap is reported.
     */
    static Optional<HabitReport.Habit> streaming(HabitHistory history,
            Function<String, String> names) {
        return PlayedWeek.STREAMED.stream().sorted()
                .flatMap(position -> held(history, position, names).stream())
                .max(Comparator.comparingDouble(
                        habit -> Double.parseDouble(habit.facts().get("gapPerWeek"))));
    }

    private static Optional<HabitReport.Habit> held(HabitHistory history, String position,
            Function<String, String> names) {
        Map<String, Integer> starts = new HashMap<>();
        for (HabitWeek week : history.scoredWeeks()) {
            startedAt(week.played().orElseThrow(), position)
                    .ifPresent(playerId -> starts.merge(playerId, 1, Integer::sum));
        }
        Optional<String> holder = starts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
        if (holder.isEmpty()) {
            return Optional.empty();
        }
        int weeks = 0;
        double heldPoints = 0;
        double freeAgentPoints = 0;
        for (HabitWeek week : history.scoredWeeks()) {
            PlayedWeek played = week.played().orElseThrow();
            PlayedWeek.FreeAgent freeAgent = played.freeAgents().get(position);
            if (freeAgent == null || !startedAt(played, position).equals(holder)) {
                continue;
            }
            weeks++;
            heldPoints += played.pointsOf(holder.get()).orElse(0.0);
            freeAgentPoints += freeAgent.scored();
        }
        double gap = weeks == 0 ? 0 : (freeAgentPoints - heldPoints) / weeks;
        if (weeks < MINIMUM_HELD_WEEKS || gap < STREAMING_GAP_PER_WEEK) {
            return Optional.empty();
        }
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("position", position);
        facts.put("heldPlayer", names.apply(holder.get()));
        facts.put("weeksStarted", String.valueOf(weeks));
        facts.put("heldPoints", HabitReport.points(heldPoints));
        facts.put("bestFreeAgentPoints", HabitReport.points(freeAgentPoints));
        facts.put("gapPerWeek", HabitReport.points(gap));
        facts.put("pointsLost", HabitReport.points(freeAgentPoints - heldPoints));
        facts.put("bestFreeAgentMeans", "each week, the free agent at the position projected "
                + "highest before the games");
        facts.put("fix", "Week-to-week scoring at %s is close to a coin flip, so try picking "
                .formatted(position) + "one up each week by matchup instead of holding one.");
        return Optional.of(new HabitReport.Habit("streaming", facts, List.of(holder.get())));
    }

    private static Optional<String> startedAt(PlayedWeek played, String position) {
        for (int slot = 0; slot < played.slots().size() && slot < played.starters().size();
                slot++) {
            if (position.equals(played.slots().get(slot))) {
                return Optional.of(played.starters().get(slot));
            }
        }
        return Optional.empty();
    }
}
