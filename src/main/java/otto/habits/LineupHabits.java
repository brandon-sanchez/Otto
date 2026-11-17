package otto.habits;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import otto.directory.PlayerHealth;
import otto.sleeper.SleeperAdapter.StarterLine;

/**
 * The habits measured from the user's lineups alone: points left on the
 * bench, chasing last week's big game, starting Questionable players and
 * favouring one NFL team.
 */
final class LineupHabits {

    /** Below this many points a week, the bench gap is ordinary noise. */
    static final double BENCH_POINTS_PER_WEEK = 3.0;

    /** Fewest cases before chasing or Questionable starts are measured. */
    static final int MINIMUM_CASES = 3;

    static final double BIG_GAME_POINTS = 15.0;

    /** A big game is also at least this multiple of the player's average before it. */
    static final double BIG_GAME_MULTIPLE = 1.5;

    /** Weeks of scoring before the big game, to know what his average is. */
    static final int WEEKS_BEFORE_BIG_GAME = 2;

    /**
     * Share of Questionable players on the final injury report who played
     * in 2025, from Footballguys' count:
     * https://www.footballguys.com/article/2025-chance-to-play-questionable-vs-doubtful
     */
    static final String QUESTIONABLE_BASE_RATE = "70.7%";
    static final String QUESTIONABLE_BASE_SEASON = "2025";

    /** A team supplies at least this share of the user's starts to count as favoured. */
    static final double FAVOURED_SHARE = 0.20;

    /** Distinct players a favoured team must supply, so one star is not a favourite team. */
    static final int FAVOURED_PLAYERS = 2;

    private LineupHabits() {
    }

    /** Points left on the bench against the best lineup in hindsight. */
    static Optional<HabitReport.Habit> bench(HabitHistory history, Function<String, String> names) {
        List<HabitWeek> weeks = history.scoredWeeks();
        double lost = 0;
        HabitWeek worst = null;
        double worstGap = 0;
        for (HabitWeek week : weeks) {
            PlayedWeek played = week.played().orElseThrow();
            double gap = Math.max(0, played.best() - played.actual());
            lost += gap;
            if (gap > worstGap) {
                worstGap = gap;
                worst = week;
            }
        }
        if (worst == null || lost / weeks.size() < BENCH_POINTS_PER_WEEK) {
            return Optional.empty();
        }
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("pointsLeftOnBench", HabitReport.points(lost));
        facts.put("weeks", String.valueOf(weeks.size()));
        facts.put("perWeek", HabitReport.points(lost / weeks.size()));
        facts.put("worstWeek", "week " + worst.week());
        facts.put("worstWeekPoints", HabitReport.points(worstGap));
        facts.put("fix", "Before the first game each week, compare your bench with your "
                + "starters; Otto's start/sit Alerts flag the bigger gaps.");
        return Optional.of(new HabitReport.Habit("bench", facts, List.of()));
    }

    /**
     * Starting a player the week after a big game he scored on someone
     * else's lineup or the user's bench, measured against the best benched
     * player who could have filled his slot.
     */
    static Optional<HabitReport.Habit> chasing(HabitHistory history,
            Function<String, String> names) {
        int cases = 0;
        double lost = 0;
        String example = null;
        Set<String> players = new HashSet<>();
        for (HabitWeek week : history.scoredWeeks()) {
            Optional<PlayedWeek> previous = history.week(week.week() - 1)
                    .flatMap(HabitWeek::played);
            if (previous.isEmpty()) {
                continue;
            }
            PlayedWeek played = week.played().orElseThrow();
            for (int slot = 0; slot < played.starters().size(); slot++) {
                String playerId = played.starters().get(slot);
                Double bigGame = previous.get().leaguePoints().get(playerId);
                if (StarterLine.EMPTY_SLOT.equals(playerId) || previous.get().started(playerId)
                        || bigGame == null
                        || !bigGame(history, week.week() - 1, playerId, bigGame)) {
                    continue;
                }
                Optional<Double> scored = played.pointsOf(playerId);
                Optional<Double> bench = played.bestBenchFor(slot);
                if (scored.isEmpty() || bench.isEmpty()) {
                    continue;
                }
                cases++;
                lost += bench.get() - scored.get();
                players.add(playerId);
                if (example == null && bench.get() > scored.get()) {
                    example = ("In week %d you started %s after he scored %s the week before; "
                            + "he scored %s while your best benched option for that spot "
                            + "scored %s.").formatted(week.week(), names.apply(playerId),
                            HabitReport.points(bigGame), HabitReport.points(scored.get()),
                            HabitReport.points(bench.get()));
                }
            }
        }
        if (cases < MINIMUM_CASES || lost <= 0) {
            return Optional.empty();
        }
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("chasedStarts", String.valueOf(cases));
        facts.put("pointsLost", HabitReport.points(lost));
        facts.put("example", example);
        facts.put("fix", "Before you start someone off one big game, check whether his snaps "
                + "and targets went up too, or whether it was one long touchdown.");
        return Optional.of(new HabitReport.Habit("chasing", facts, List.copyOf(players)));
    }

    private static boolean bigGame(HabitHistory history, int week, String playerId,
            double points) {
        List<Double> before = history.scoredWeeks().stream()
                .filter(earlier -> earlier.week() < week)
                .map(earlier -> earlier.played().orElseThrow().leaguePoints().get(playerId))
                .filter(Objects::nonNull)
                .toList();
        if (before.size() < WEEKS_BEFORE_BIG_GAME) {
            return false;
        }
        double average = before.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return points >= BIG_GAME_POINTS && points >= BIG_GAME_MULTIPLE * average;
    }

    /**
     * Starting players listed Questionable at kickoff, against how often
     * Questionable players play. Scoring nothing is read as sitting out,
     * the closest Sleeper's points come to an inactive list.
     */
    static Optional<HabitReport.Habit> questionable(HabitHistory history,
            Function<String, String> names) {
        int starts = 0;
        int satOut = 0;
        double lost = 0;
        Set<String> players = new HashSet<>();
        for (HabitWeek week : history.scoredWeeks()) {
            PlayedWeek played = week.played().orElseThrow();
            for (int slot = 0; slot < played.starters().size(); slot++) {
                String playerId = played.starters().get(slot);
                if (week.kickoff().get(playerId) != PlayerHealth.QUESTIONABLE) {
                    continue;
                }
                starts++;
                players.add(playerId);
                if (played.pointsOf(playerId).orElse(0.0) == 0) {
                    satOut++;
                    lost += played.bestBenchFor(slot).orElse(0.0);
                }
            }
        }
        if (starts < MINIMUM_CASES || lost <= 0) {
            return Optional.empty();
        }
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("questionableStarts", String.valueOf(starts));
        facts.put("played", String.valueOf(starts - satOut));
        facts.put("satOut", String.valueOf(satOut));
        facts.put("playedShare", Math.round(100.0 * (starts - satOut) / starts) + "%");
        facts.put("baseRate", QUESTIONABLE_BASE_RATE);
        facts.put("baseRateSeason", QUESTIONABLE_BASE_SEASON);
        facts.put("pointsLost", HabitReport.points(lost));
        facts.put("players", String.join(", ", players.stream().map(names).sorted().toList()));
        facts.put("fix", "When a Questionable starter plays late, keep a healthy backup who "
                + "plays later in the slot, or check the inactive list before his game.");
        return Optional.of(new HabitReport.Habit("questionable", facts, List.copyOf(players)));
    }

    /**
     * Starting one NFL team's players more than the rest, measured by what
     * those starts lost to the bench beyond what his other starts lost.
     */
    static Optional<HabitReport.Habit> favouredTeam(HabitHistory history,
            Function<String, String> names) {
        Map<String, Integer> starts = new HashMap<>();
        Map<String, Double> lostTo = new HashMap<>();
        Map<String, Set<String>> players = new HashMap<>();
        int allStarts = 0;
        double allLost = 0;
        for (HabitWeek week : history.scoredWeeks()) {
            PlayedWeek played = week.played().orElseThrow();
            for (int slot = 0; slot < played.starters().size(); slot++) {
                String playerId = played.starters().get(slot);
                String team = played.teams().get(playerId);
                if (team == null) {
                    continue;
                }
                double lost = Math.max(0, played.bestBenchFor(slot).orElse(0.0)
                        - played.pointsOf(playerId).orElse(0.0));
                allStarts++;
                allLost += lost;
                starts.merge(team, 1, Integer::sum);
                lostTo.merge(team, lost, Double::sum);
                players.computeIfAbsent(team, key -> new HashSet<>()).add(playerId);
            }
        }
        List<HabitReport.Habit> favoured = new ArrayList<>();
        for (Map.Entry<String, Integer> team : starts.entrySet()) {
            int teamStarts = team.getValue();
            double lost = lostTo.get(team.getKey());
            int otherStarts = allStarts - teamStarts;
            double otherRate = otherStarts == 0 ? 0 : (allLost - lost) / otherStarts;
            if ((double) teamStarts / allStarts < FAVOURED_SHARE
                    || players.get(team.getKey()).size() < FAVOURED_PLAYERS
                    || lost <= 0 || lost / teamStarts <= otherRate) {
                continue;
            }
            Map<String, String> facts = new LinkedHashMap<>();
            facts.put("team", team.getKey());
            facts.put("teamStarts", String.valueOf(teamStarts));
            facts.put("shareOfStarts", Math.round(100.0 * teamStarts / allStarts) + "%");
            facts.put("pointsLost", HabitReport.points(lost));
            facts.put("players", String.join(", ",
                    players.get(team.getKey()).stream().map(names).sorted().toList()));
            facts.put("fix", "When one of your %s players is a close call, judge him on his "
                    .formatted(team.getKey()) + "role and matchup like anyone else.");
            favoured.add(new HabitReport.Habit("team", facts,
                    List.copyOf(players.get(team.getKey()))));
        }
        return favoured.stream().max(Comparator.comparing(
                habit -> Double.parseDouble(habit.facts().get("pointsLost"))));
    }
}
