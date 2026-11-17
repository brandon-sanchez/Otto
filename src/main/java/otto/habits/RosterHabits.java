package otto.habits;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import otto.advice.Prose;
import otto.directory.PlayerHealth;
import otto.sleeper.SleeperAdapter.StarterLine;

/**
 * The habits measured from who could play: starting a player who was
 * ruled out or on bye, letting byes pile up on core starters, and
 * holding roster spots on players who can't help.
 */
final class RosterHabits {

    /** One preventable zero is worth mentioning: it is points nothing gives back. */
    static final int MINIMUM_DEAD_STARTS = 1;

    /** Core starters at one position sharing a bye that make a pile-up. */
    static final int PILE_UP_AT_POSITION = 2;

    /** Core starters across the lineup sharing a bye that make a pile-up. */
    static final int PILE_UP_OVERALL = 3;

    /**
     * A core starter started in at least this share of the weeks he was
     * on the roster and had a game.
     */
    static final double CORE_SHARE = 0.5;

    /** Fewest starts before a player counts as a core starter. */
    static final int CORE_STARTS = 2;

    /** Scoreless weeks in a row before a player is called a clogged spot. */
    static final int SCORELESS_WEEKS = 3;

    private RosterHabits() {
    }

    /**
     * Starting a player who was ruled out before kickoff or on bye and
     * scored nothing, while a benched player who could fill the slot
     * scored.
     */
    static Optional<HabitReport.Habit> missedSwaps(HabitHistory history,
            Function<String, String> names) {
        int cases = 0;
        double lost = 0;
        double worst = 0;
        String example = null;
        Set<String> players = new LinkedHashSet<>();
        Set<Integer> weeks = new LinkedHashSet<>();
        for (HabitWeek week : history.scoredWeeks()) {
            PlayedWeek played = week.played().orElseThrow();
            for (int slot = 0; slot < played.starters().size(); slot++) {
                String playerId = played.starters().get(slot);
                Optional<String> reason = deadReason(week, played, playerId);
                Optional<Double> bench = played.bestBenchFor(slot);
                if (reason.isEmpty() || played.pointsOf(playerId).orElse(0.0) > 0
                        || bench.isEmpty() || bench.get() <= 0) {
                    continue;
                }
                cases++;
                lost += bench.get();
                players.add(playerId);
                weeks.add(week.week());
                if (bench.get() > worst) {
                    worst = bench.get();
                    example = ("In week %d you started %s, who was %s and scored 0, while "
                            + "your best benched option for that spot scored %s.")
                            .formatted(week.week(), names.apply(playerId), reason.get(),
                                    HabitReport.points(bench.get()));
                }
            }
        }
        if (cases < MINIMUM_DEAD_STARTS) {
            return Optional.empty();
        }
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("deadStarts", String.valueOf(cases));
        facts.put("pointsLost", HabitReport.points(lost));
        facts.put("weeks", weeks.stream().map(week -> "week " + week)
                .collect(Collectors.joining(", ")));
        facts.put("example", example);
        facts.put("fix", "Before the first game each week, check that none of your starters is "
                + "ruled out or on bye; any benched player who is playing beats a zero.");
        return Optional.of(new HabitReport.Habit("swaps", facts, List.copyOf(players)));
    }

    private static Optional<String> deadReason(HabitWeek week, PlayedWeek played,
            String playerId) {
        if (StarterLine.EMPTY_SLOT.equals(playerId)) {
            return Optional.empty();
        }
        if (played.onBye(playerId)) {
            return Optional.of("on bye");
        }
        PlayerHealth health = week.kickoff().get(playerId);
        return health != null && health.rulesOutPlaying()
                ? Optional.of(designation(health))
                : Optional.empty();
    }

    private static String designation(PlayerHealth health) {
        return switch (health) {
            case IR -> "on IR";
            case PUP -> "on the PUP list";
            case SUSPENDED -> "suspended";
            case NOT_ACTIVE, DID_NOT_REPORT -> "inactive";
            default -> "ruled out";
        };
    }

    /**
     * Weeks when core starters shared a bye, two or more at one position
     * or three or more overall, measured by how far the lineup fell below
     * what it scored in the other weeks.
     */
    static Optional<HabitReport.Habit> byePileUps(HabitHistory history,
            Function<String, String> names) {
        List<HabitWeek> weeks = history.scoredWeeks();
        Set<String> core = coreStarters(weeks);
        Map<Integer, List<String>> pileUps = new LinkedHashMap<>();
        for (HabitWeek week : weeks) {
            PlayedWeek played = week.played().orElseThrow();
            List<String> away = core.stream()
                    .filter(playerId -> played.points().containsKey(playerId))
                    .filter(played::onBye)
                    .sorted()
                    .toList();
            Map<String, Long> byPosition = away.stream()
                    .collect(Collectors.groupingBy(
                            playerId -> played.positions().getOrDefault(playerId, playerId),
                            Collectors.counting()));
            if (away.size() >= PILE_UP_OVERALL || byPosition.values().stream()
                    .anyMatch(count -> count >= PILE_UP_AT_POSITION)) {
                pileUps.put(week.week(), away);
            }
        }
        List<Double> usual = weeks.stream()
                .filter(week -> !pileUps.containsKey(week.week()))
                .map(week -> week.played().orElseThrow().actual())
                .toList();
        if (pileUps.isEmpty() || usual.isEmpty()) {
            return Optional.empty();
        }
        double usualScore = usual.stream().mapToDouble(Double::doubleValue).average()
                .orElseThrow();
        double below = 0;
        for (HabitWeek week : weeks) {
            if (pileUps.containsKey(week.week())) {
                below += Math.max(0, usualScore - week.played().orElseThrow().actual());
            }
        }
        if (below <= 0) {
            return Optional.empty();
        }
        Map.Entry<Integer, List<String>> first = pileUps.entrySet().iterator().next();
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("pileUpWeeks", pileUps.keySet().stream().map(week -> "week " + week)
                .collect(Collectors.joining(", ")));
        facts.put("usualScore", HabitReport.points(usualScore));
        facts.put("pointsBelowUsual", HabitReport.points(below));
        facts.put("example", "In week %d, %s were all on bye the same week.".formatted(
                first.getKey(), Prose.listed(first.getValue().stream().map(names).toList())));
        facts.put("fix", "When you add or trade for a starter, check his bye against your other "
                + "starters' so no single week takes out several at once.");
        Set<String> players = pileUps.values().stream().flatMap(List::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return Optional.of(new HabitReport.Habit("byes", facts, List.copyOf(players)));
    }

    /**
     * The players the user starts most weeks: started in at least
     * {@link #CORE_SHARE} of the weeks he was rostered and had a game.
     */
    private static Set<String> coreStarters(List<HabitWeek> weeks) {
        Map<String, Integer> available = new HashMap<>();
        Map<String, Integer> started = new HashMap<>();
        for (HabitWeek week : weeks) {
            PlayedWeek played = week.played().orElseThrow();
            for (String playerId : played.points().keySet()) {
                if (played.onBye(playerId)) {
                    continue;
                }
                available.merge(playerId, 1, Integer::sum);
                if (played.started(playerId)) {
                    started.merge(playerId, 1, Integer::sum);
                }
            }
        }
        return started.entrySet().stream()
                .filter(entry -> entry.getValue() >= CORE_STARTS
                        && entry.getValue() >= CORE_SHARE * available.get(entry.getKey()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /**
     * Holding bench spots on players who can't help: one eligible for IR
     * while an IR slot sat open, or one scoreless for
     * {@link #SCORELESS_WEEKS} weeks running. Measured by the starting
     * slots that scored nothing in those weeks because no benched player
     * could fill them, each at what that slot usually scores for him.
     */
    static Optional<HabitReport.Habit> clogging(HabitHistory history,
            Function<String, String> names) {
        List<HabitWeek> weeks = history.scoredWeeks();
        Map<Integer, Set<String>> clogged = cloggedSpots(weeks);
        double lost = 0;
        List<String> holes = new ArrayList<>();
        for (HabitWeek week : weeks) {
            Set<String> spots = clogged.getOrDefault(week.week(), Set.of());
            if (spots.isEmpty()) {
                continue;
            }
            PlayedWeek played = week.played().orElseThrow();
            for (int slot = 0; slot < played.starters().size(); slot++) {
                String playerId = played.starters().get(slot);
                boolean dead = StarterLine.EMPTY_SLOT.equals(playerId)
                        || played.pointsOf(playerId).orElse(0.0) == 0;
                if (!dead || played.bestBenchFor(slot).orElse(0.0) > 0) {
                    continue;
                }
                double usual = usualAt(weeks, slot, week.week());
                if (usual > 0) {
                    lost += usual;
                    holes.add("week " + week.week());
                }
            }
        }
        if (lost <= 0) {
            return Optional.empty();
        }
        Set<String> players = clogged.values().stream().flatMap(Set::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("emptyStartsWithNoBackup", String.valueOf(holes.size()));
        facts.put("weeks", String.join(", ", new LinkedHashSet<>(holes)));
        facts.put("pointsLost", HabitReport.points(lost));
        facts.put("cloggedBy", Prose.listed(players.stream().map(names).sorted().toList()));
        facts.put("fix", "Move anyone eligible to IR when a slot is open, and cut a player "
                + "who keeps scoring nothing, so a playable backup has a spot.");
        return Optional.of(new HabitReport.Habit("clogging", facts, List.copyOf(players)));
    }

    private static Map<Integer, Set<String>> cloggedSpots(List<HabitWeek> weeks) {
        Map<Integer, Set<String>> clogged = new HashMap<>();
        Map<String, Integer> scoreless = new HashMap<>();
        for (HabitWeek week : weeks) {
            PlayedWeek played = week.played().orElseThrow();
            Set<String> spots = new LinkedHashSet<>(week.couldGoOnIr());
            Map<String, Integer> running = new HashMap<>();
            for (Map.Entry<String, Double> player : played.points().entrySet()) {
                String playerId = player.getKey();
                if (played.onBye(playerId) && scoreless.containsKey(playerId)) {
                    running.put(playerId, scoreless.get(playerId));
                    continue;
                }
                if (player.getValue() != 0 || week.reserve().contains(playerId)) {
                    continue;
                }
                int streak = scoreless.getOrDefault(playerId, 0) + 1;
                running.put(playerId, streak);
                if (streak >= SCORELESS_WEEKS && !played.started(playerId)) {
                    spots.add(playerId);
                }
            }
            scoreless.clear();
            scoreless.putAll(running);
            spots.removeIf(played::started);
            clogged.put(week.week(), spots);
        }
        return clogged;
    }

    /** What the slot scored for him on average in the other weeks it scored at all. */
    private static double usualAt(List<HabitWeek> weeks, int slot, int except) {
        return weeks.stream()
                .filter(week -> week.week() != except)
                .map(week -> week.played().orElseThrow())
                .filter(played -> slot < played.starters().size())
                .mapToDouble(played -> played.pointsOf(played.starters().get(slot)).orElse(0.0))
                .filter(points -> points > 0)
                .average()
                .orElse(0);
    }
}
