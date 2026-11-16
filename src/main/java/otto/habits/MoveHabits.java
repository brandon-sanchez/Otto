package otto.habits;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The habits measured from the user's transactions: dropping a player
 * after one bad game, and paying more FAAB than a claim needed.
 */
final class MoveHabits {

    /** Fewest drops after a bad game before dropping is measured at all. */
    static final int MINIMUM_DROPS = 3;

    /** Weeks after a drop over which the dropped and added players are compared. */
    static final int WEEKS_AFTER_DROP = 4;

    /** Fewest winning FAAB bids of the user's before overpaying is measured. */
    static final int MINIMUM_WINNING_BIDS = 5;

    /** Fewest rival winning bids before the league's own habit is a fair comparison. */
    static final int MINIMUM_LEAGUE_BIDS = 5;

    private static final Set<String> ROSTER_MOVES = Set.of("waiver", "free_agent");

    private MoveHabits() {
    }

    /** One drop made right after the dropped player's below-average game. */
    private record Drop(int week, String dropped, String added, double badGame,
            double droppedAfter, double addedAfter) {

        double regret() {
            return droppedAfter - addedAfter;
        }
    }

    /**
     * Dropping a player the week after he scored below his own average,
     * measured by what he then scored next to the player added for him
     * over the following weeks.
     */
    static Optional<HabitReport.Habit> drops(HabitHistory history,
            Function<String, String> names) {
        List<Drop> drops = new ArrayList<>();
        for (HabitWeek week : history.weeks()) {
            for (HabitWeek.Move move : week.moves()) {
                if (!move.won() || !ROSTER_MOVES.contains(move.type())
                        || move.added().isEmpty()) {
                    continue;
                }
                move.dropped().forEach(dropped -> afterBadGame(history, week.week(), dropped,
                        move.added().getFirst()).ifPresent(drops::add));
            }
        }
        double lost = drops.stream().mapToDouble(Drop::regret).sum();
        if (drops.size() < MINIMUM_DROPS || lost <= 0) {
            return Optional.empty();
        }
        Drop worst = drops.stream().max(Comparator.comparingDouble(Drop::regret)).orElseThrow();
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("dropsAfterABadGame", String.valueOf(drops.size()));
        facts.put("droppedOutscoredAdded", String.valueOf(drops.stream()
                .filter(drop -> drop.regret() > 0).count()));
        facts.put("weeksCompared", String.valueOf(WEEKS_AFTER_DROP));
        facts.put("pointsLost", HabitReport.points(lost));
        facts.put("example", ("In week %d you dropped %s after a %s-point game and added %s; "
                + "over the next %d weeks %s scored %s and %s scored %s.").formatted(
                worst.week(), names.apply(worst.dropped()), HabitReport.points(worst.badGame()),
                names.apply(worst.added()), WEEKS_AFTER_DROP, names.apply(worst.dropped()),
                HabitReport.points(worst.droppedAfter()), names.apply(worst.added()),
                HabitReport.points(worst.addedAfter())));
        facts.put("fix", "Before you cut someone after one quiet week, check whether his snaps "
                + "and targets held up; one bad game says little on its own.");
        Set<String> players = new LinkedHashSet<>();
        drops.forEach(drop -> {
            players.add(drop.dropped());
            players.add(drop.added());
        });
        return Optional.of(new HabitReport.Habit("drops", facts, List.copyOf(players)));
    }

    /**
     * The drop, when the dropped player's last game for the user was below
     * his average in the games before it and weeks have been scored since.
     */
    private static Optional<Drop> afterBadGame(HabitHistory history, int week, String dropped,
            String added) {
        List<Double> games = history.scoredWeeks().stream()
                .filter(earlier -> earlier.week() < week)
                .flatMap(earlier -> earlier.played().orElseThrow().pointsOf(dropped).stream())
                .toList();
        List<PlayedWeek> after = history.scoredWeeks().stream()
                .filter(later -> later.week() > week && later.week() <= week + WEEKS_AFTER_DROP)
                .map(later -> later.played().orElseThrow())
                .toList();
        if (games.size() < 2 || after.isEmpty()) {
            return Optional.empty();
        }
        double badGame = games.getLast();
        double average = games.subList(0, games.size() - 1).stream()
                .mapToDouble(Double::doubleValue).average().orElseThrow();
        if (badGame >= average) {
            return Optional.empty();
        }
        return Optional.of(new Drop(week, dropped, added, badGame,
                scoredAfter(after, dropped), scoredAfter(after, added)));
    }

    private static double scoredAfter(List<PlayedWeek> weeks, String playerId) {
        return weeks.stream()
                .mapToDouble(played -> played.scoredAnywhere(playerId).orElse(0.0))
                .sum();
    }

    /**
     * Paying more for FAAB claims than they needed: each winning bid
     * against one dollar over the next-highest bid on the same player, or
     * the league's minimum bid when nobody else bid. Reported only when
     * the user overpays by a larger share than the rest of his league.
     */
    static Optional<HabitReport.Habit> faab(HabitHistory history,
            Function<String, String> names) {
        Set<String> userClaims = history.weeks().stream()
                .flatMap(week -> week.moves().stream())
                .filter(move -> move.won() && move.bid().isPresent())
                .map(HabitWeek.Move::transactionId)
                .collect(Collectors.toSet());
        List<HabitWeek.Clearing> all = history.weeks().stream()
                .flatMap(week -> week.clearings().stream())
                .toList();
        List<HabitWeek.Clearing> user = all.stream()
                .filter(clearing -> userClaims.contains(clearing.transactionId()))
                .toList();
        List<HabitWeek.Clearing> league = all.stream()
                .filter(clearing -> !userClaims.contains(clearing.transactionId()))
                .toList();
        int wasted = user.stream().mapToInt(HabitWeek.Clearing::overpay).sum();
        if (user.size() < MINIMUM_WINNING_BIDS || wasted <= 0) {
            return Optional.empty();
        }
        double userShare = medianShare(user).orElse(0);
        OptionalDouble leagueShare = league.size() >= MINIMUM_LEAGUE_BIDS
                ? medianShare(league)
                : OptionalDouble.empty();
        if (leagueShare.isPresent() && userShare <= leagueShare.getAsDouble()) {
            return Optional.empty();
        }
        HabitWeek.Clearing worst = user.stream()
                .max(Comparator.comparingInt(HabitWeek.Clearing::overpay)).orElseThrow();
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("winningBids", String.valueOf(user.size()));
        facts.put("dollarsSpent", "$" + user.stream().mapToInt(HabitWeek.Clearing::bid).sum());
        facts.put("dollarsAboveWinningPrice", "$" + wasted);
        facts.put("medianOverpayShare", percent(userShare));
        leagueShare.ifPresent(share -> facts.put("leagueMedianOverpayShare", percent(share)));
        facts.put("comparedWith", "the next-highest bid on the same player in the same waiver "
                + "run, which Sleeper reports for every lost claim, or the league's minimum bid "
                + "when nobody else bid");
        facts.put("example", worst.runnerUp()
                .map(runnerUp -> "You bid $%d for %s when the next-highest bid was $%d."
                        .formatted(worst.bid(), names.apply(worst.playerId()), runnerUp))
                .orElseGet(() -> "You bid $%d for %s when nobody else bid on him."
                        .formatted(worst.bid(), names.apply(worst.playerId()))));
        facts.put("fix", "Before you bid, think about who else needs that position; a few "
                + "dollars over the likely next bid usually wins it.");
        return Optional.of(new HabitReport.Habit("faab", facts, List.of(worst.playerId())));
    }

    /** The median share of each winning bid that was paid above what would have won. */
    private static OptionalDouble medianShare(List<HabitWeek.Clearing> clearings) {
        List<Double> shares = clearings.stream()
                .filter(clearing -> clearing.bid() > 0)
                .map(clearing -> (double) clearing.overpay() / clearing.bid())
                .sorted()
                .toList();
        if (shares.isEmpty()) {
            return OptionalDouble.empty();
        }
        int middle = shares.size() / 2;
        return OptionalDouble.of(shares.size() % 2 == 1
                ? shares.get(middle)
                : (shares.get(middle - 1) + shares.get(middle)) / 2);
    }

    private static String percent(double share) {
        return Math.round(100 * share) + "%";
    }
}
