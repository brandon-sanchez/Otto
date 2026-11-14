package otto.nflverse;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Season-to-date pressure rates for every defense and every passer,
 * computed from the stored PFR files.
 *
 * A dropback count comes only from a passer's own pressure percentage
 * (see {@link PfrPassing.PasserLine#dropbacks()}), so a passer who was
 * never pressured in a game has no count for it. That game is left out
 * of every rate it would feed, and counted as unmeasured, rather than
 * guessed at.
 *
 * @param season the season both files cover
 * @param priorSeasonFinal true when these are last season's final rates
 * @param defenses every defense, the highest rate first and the
 *        unmeasured last
 * @param passers every passer, the highest rate first and the unmeasured last
 */
public record PressureRates(String season, boolean priorSeasonFinal,
        List<DefenseRate> defenses, List<PasserRate> passers) {

    public PressureRates {
        defenses = List.copyOf(defenses);
        passers = List.copyOf(passers);
    }

    /**
     * How often one defense pressured the passers it faced.
     *
     * @param team the defense, in Sleeper's vocabulary
     * @param games the games measured
     * @param unmeasuredGames games left out because an opposing passer's
     *        dropbacks are unknown
     * @param pressures its players' pressures summed over the measured games
     * @param opposingDropbacks the opposing passers' dropbacks in those games
     */
    public record DefenseRate(String team, int games, int unmeasuredGames, int pressures,
            int opposingDropbacks) {

        /** Pressures per opposing dropback; empty when no game was measured. */
        public OptionalDouble rate() {
            return ratio(pressures, opposingDropbacks);
        }

        DefenseRate plus(PfrDefense.GamePressures game, OptionalInt dropbacks) {
            return dropbacks.isPresent()
                    ? new DefenseRate(team, games + 1, unmeasuredGames,
                            pressures + game.pressures(), opposingDropbacks + dropbacks.getAsInt())
                    : new DefenseRate(team, games, unmeasuredGames + 1, pressures,
                            opposingDropbacks);
        }
    }

    /**
     * How often one passer was pressured.
     *
     * @param pfrId his Pro Football Reference id
     * @param player his name as PFR writes it
     * @param team his team in his latest game, in Sleeper's vocabulary
     * @param games the games measured
     * @param unmeasuredGames games in which he was never pressured, so his
     *        dropbacks are unknown
     * @param timesPressured his pressures over the measured games
     * @param dropbacks his dropbacks over the measured games
     */
    public record PasserRate(String pfrId, String player, String team, int games,
            int unmeasuredGames, int timesPressured, int dropbacks) {

        /**
         * Pressures per dropback, the season's {@code times_pressured_pct};
         * empty when no game was measured.
         */
        public OptionalDouble rate() {
            return ratio(timesPressured, dropbacks);
        }

        PasserRate plus(PfrPassing.PasserLine line) {
            OptionalInt game = line.dropbacks();
            return game.isPresent()
                    ? new PasserRate(pfrId, player, line.team(), games + 1, unmeasuredGames,
                            timesPressured + line.timesPressured(), dropbacks + game.getAsInt())
                    : new PasserRate(pfrId, player, line.team(), games, unmeasuredGames + 1,
                            timesPressured, dropbacks);
        }
    }

    /**
     * Computes the rates from both files.
     *
     * @return empty when the two files cover different seasons, as they
     *         do for the hour between one being refreshed and the other
     */
    public static Optional<PressureRates> of(PfrDefense defense, PfrPassing passing) {
        if (!defense.season().equals(passing.season())) {
            return Optional.empty();
        }
        return Optional.of(new PressureRates(defense.season(), defense.priorSeasonFinal(),
                defenses(defense, passing), passers(passing)));
    }

    /** Returns one defense's rate, by its Sleeper team code; empty when it has no rows. */
    public Optional<DefenseRate> defense(String team) {
        return defenses.stream().filter(rate -> rate.team().equals(team)).findFirst();
    }

    /** Returns one passer's rate, by his PFR id; empty when he has no rows. */
    public Optional<PasserRate> passer(String pfrId) {
        return passers.stream().filter(rate -> rate.pfrId().equals(pfrId)).findFirst();
    }

    private static List<DefenseRate> defenses(PfrDefense defense, PfrPassing passing) {
        Map<List<String>, List<PfrPassing.PasserLine>> passersByGame = passing.rows().stream()
                .collect(Collectors.groupingBy(line -> List.of(line.gameId(), line.team())));
        Map<String, DefenseRate> byTeam = new TreeMap<>();
        for (PfrDefense.GamePressures game : defense.rows()) {
            OptionalInt dropbacks = opposingDropbacks(
                    passersByGame.getOrDefault(List.of(game.gameId(), game.opponent()), List.of()));
            byTeam.put(game.team(), byTeam
                    .getOrDefault(game.team(), new DefenseRate(game.team(), 0, 0, 0, 0))
                    .plus(game, dropbacks));
        }
        return ranked(byTeam.values(), DefenseRate::rate);
    }

    private static OptionalInt opposingDropbacks(List<PfrPassing.PasserLine> passers) {
        if (passers.isEmpty()) {
            return OptionalInt.empty();
        }
        int total = 0;
        for (PfrPassing.PasserLine passer : passers) {
            OptionalInt dropbacks = passer.dropbacks();
            if (dropbacks.isEmpty()) {
                return OptionalInt.empty();
            }
            total += dropbacks.getAsInt();
        }
        return OptionalInt.of(total);
    }

    private static List<PasserRate> passers(PfrPassing passing) {
        Map<String, PasserRate> byPasser = new TreeMap<>();
        List<PfrPassing.PasserLine> byWeek = passing.rows().stream()
                .sorted(Comparator.comparingInt(PfrPassing.PasserLine::week))
                .toList();
        for (PfrPassing.PasserLine line : byWeek) {
            PasserRate none = new PasserRate(line.pfrId(), line.player(), line.team(), 0, 0, 0, 0);
            byPasser.put(line.pfrId(), byPasser.getOrDefault(line.pfrId(), none).plus(line));
        }
        return ranked(byPasser.values(), PasserRate::rate);
    }

    private static <T> List<T> ranked(Collection<T> rates, Function<T, OptionalDouble> rate) {
        List<T> ordered = new ArrayList<>(rates);
        ordered.sort(Comparator.comparingDouble(
                (T each) -> rate.apply(each).orElse(Double.NEGATIVE_INFINITY)).reversed());
        return ordered;
    }

    private static OptionalDouble ratio(int numerator, int denominator) {
        return denominator == 0
                ? OptionalDouble.empty()
                : OptionalDouble.of((double) numerator / denominator);
    }
}
