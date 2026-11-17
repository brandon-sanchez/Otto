package otto.habits;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * How following or overriding Otto's start/sit and waiver calls turned
 * out for the user.
 */
final class AdviceHabits {

    /** Fewest start/sit calls he followed or overrode before the habit is measured. */
    static final int MINIMUM_CALLS = 3;

    /** Fewest overrides before overriding is called a habit. */
    static final int MINIMUM_OVERRIDES = 2;

    /** How long after a waiver call a claim of the same player counts as following it. */
    static final Duration CLAIM_WINDOW = Duration.ofDays(7);

    /** Weeks after a waiver call over which a passed-up pick's points are counted. */
    static final int WEEKS_AFTER_CLAIM = 4;

    private AdviceHabits() {
    }

    /** One start/sit call the user either followed or overrode. */
    record Judged(AdviceCall call, boolean followed, double started, double sat) {

        /** What starting Otto's pick gained over the player Otto said to sit. */
        double margin() {
            return started - sat;
        }
    }

    /**
     * Overriding Otto's start/sit calls, measured by what Otto's pick
     * outscored the player the user kept. Reported only when the overrides
     * cost him points on balance; the waiver calls ride along as context.
     */
    static Optional<HabitReport.Habit> overrides(HabitHistory history,
            Function<String, String> names) {
        List<Judged> judged = history.calls().stream()
                .filter(call -> call.kind() == AdviceCall.Kind.START)
                .flatMap(call -> judge(history, call).stream())
                .toList();
        List<Judged> overridden = judged.stream().filter(call -> !call.followed()).toList();
        double lost = overridden.stream().mapToDouble(Judged::margin).sum();
        if (judged.size() < MINIMUM_CALLS || overridden.size() < MINIMUM_OVERRIDES
                || lost <= 0) {
            return Optional.empty();
        }
        Judged costliest = overridden.stream()
                .max(Comparator.comparingDouble(Judged::margin))
                .orElseThrow();
        String start = costliest.call().playerId();
        String sit = costliest.call().over().orElseThrow();
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("startSitCalls", String.valueOf(judged.size()));
        facts.put("followed", String.valueOf(judged.size() - overridden.size()));
        facts.put("overridden", String.valueOf(overridden.size()));
        facts.put("pointsLostOverriding", HabitReport.points(lost));
        if (overridden.size() < judged.size()) {
            facts.put("pointsGainedFollowing", HabitReport.points(judged.stream()
                    .filter(Judged::followed).mapToDouble(Judged::margin).sum()));
        }
        facts.put("costliestOverride", ("In week %d Otto said to start %s over %s; you kept "
                + "%s, who scored %s to %s's %s.").formatted(costliest.call().week(),
                names.apply(start), names.apply(sit), names.apply(sit),
                HabitReport.points(costliest.sat()), names.apply(start),
                HabitReport.points(costliest.started())));
        facts.putAll(waiverCalls(history));
        facts.put("fix", "When Otto's start/sit call and your gut disagree on a close one, "
                + "try going with the call for a few weeks and see how it lands.");
        Set<String> players = new LinkedHashSet<>(List.of(start, sit));
        return Optional.of(new HabitReport.Habit("overrides", facts, List.copyOf(players)));
    }

    /**
     * Returns the start/sit call as the user acted on it, or empty when
     * he started both players or neither, or either has no score.
     */
    static Optional<Judged> judge(HabitHistory history, AdviceCall call) {
        Optional<PlayedWeek> played = history.week(call.week()).flatMap(HabitWeek::played);
        if (played.isEmpty() || call.over().isEmpty()) {
            return Optional.empty();
        }
        String start = call.playerId();
        String sit = call.over().get();
        boolean followed = played.get().started(start) && !played.get().started(sit);
        boolean overridden = played.get().started(sit) && !played.get().started(start);
        Optional<Double> startPoints = played.get().pointsOf(start);
        Optional<Double> sitPoints = played.get().pointsOf(sit);
        if (!followed && !overridden || startPoints.isEmpty() || sitPoints.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Judged(call, followed, startPoints.get(), sitPoints.get()));
    }

    /**
     * The waiver calls: how many he claimed, and what the picks he passed
     * on scored afterwards on whichever team held them.
     */
    private static Map<String, String> waiverCalls(HabitHistory history) {
        List<AdviceCall> claims = history.calls().stream()
                .filter(call -> call.kind() == AdviceCall.Kind.CLAIM)
                .toList();
        if (claims.isEmpty()) {
            return Map.of();
        }
        List<AdviceCall> passed = claims.stream()
                .filter(call -> !claimed(history, call))
                .toList();
        double scored = passed.stream()
                .mapToDouble(call -> history.scoredWeeks().stream()
                        .filter(week -> week.week() > call.week()
                                && week.week() <= call.week() + WEEKS_AFTER_CLAIM)
                        .mapToDouble(week -> week.played().orElseThrow().leaguePoints()
                                .getOrDefault(call.playerId(), 0.0))
                        .sum())
                .sum();
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("waiverCalls", String.valueOf(claims.size()));
        facts.put("waiverCallsClaimed", String.valueOf(claims.size() - passed.size()));
        if (!passed.isEmpty()) {
            facts.put("passedPicksScoredAfter", HabitReport.points(scored));
        }
        return facts;
    }

    private static boolean claimed(HabitHistory history, AdviceCall call) {
        return history.weeks().stream()
                .flatMap(week -> week.moves().stream())
                .anyMatch(move -> move.won() && move.added().contains(call.playerId())
                        && !move.at().isBefore(call.at())
                        && move.at().isBefore(call.at().plus(CLAIM_WINDOW)));
    }
}
