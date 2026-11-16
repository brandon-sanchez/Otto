package otto.matchup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * One stat ranked across the league: every player at one position, or
 * every defense against one position.
 *
 * <p>Ranks follow the football analyst's convention. Among defenses,
 * rank 1 is the toughest matchup and the last the easiest, so "1st in
 * fantasy points allowed" allows the fewest and "1st in pressure rate"
 * pressures the most, the same way round as {@code DefenseVersusPosition}.
 * Among remaining schedules, rank 1 is the hardest. Among players, rank 1
 * is the most of the stat. {@link #tone(Placement)} says whether a rank is good or bad news
 * for the fantasy player.
 *
 * <p>Equal values share a rank, so two players tied for second are both
 * 2nd and the next is 4th. {@link Placement#rankFromBottom()} counts the
 * same way from the other end, for "bottom 5" or "last".
 *
 * @param stat the stat ranked
 * @param position the position the players play, the defenses face, or
 *        the remaining schedules are rated for; null for a player stat
 *        ranked across every position
 * @param weeks the weeks the values are drawn from
 * @param population who is ranked, which is everyone in {@code ranked}
 * @param ranked the ranked entries, rank 1 first; ties are in id order
 * @param unranked the entries that were measured but not ranked, in id order
 * @param unmatched the source's names that the player register cannot
 *        place at any position, so they are in no population; sorted
 */
public record Ranking(RankedStat stat, Position position, Weeks weeks, Population population,
        List<Placement> ranked, List<Unranked> unranked, List<String> unmatched) {

    public Ranking {
        ranked = List.copyOf(ranked);
        unranked = List.copyOf(unranked);
        unmatched = List.copyOf(unmatched);
    }

    /**
     * One player's or defense's value before it is ranked.
     *
     * @param id a gsis id for a player, a Sleeper team code for a defense
     *        or a remaining schedule
     * @param name the name to show, or null when no stored source names
     *        the player; a team's is its team code
     * @param value the stat, or null when no stored source holds it. A
     *        null is never read as zero.
     * @param sample how much the value rests on, in {@link RankedStat#sampleUnit()}
     */
    public record Measurement(String id, String name, Double value, int sample) {

        /** Builds a measurement from a computed rate, which is empty when nothing was measured. */
        public Measurement(String id, String name, OptionalDouble value, int sample) {
            this(id, name, value.isPresent() ? value.getAsDouble() : null, sample);
        }
    }

    /**
     * Where one entry ranks.
     *
     * @param rank 1 is the first of the ranking: the most of a player
     *        stat, or the toughest defense
     * @param rankFromBottom 1 is the last of the ranking
     */
    public record Placement(String id, String name, double value, int sample, int rank,
            int rankFromBottom) {
    }

    /** Why a measured entry has no rank. */
    public enum Reason {

        /** Its sample is under {@link RankedStat#minimumSample()}. */
        BELOW_MINIMUM,

        /** No stored source holds the value. */
        MISSING
    }

    /** An entry left out of the ranking, and why. */
    public record Unranked(String id, String name, Reason reason, int sample) {
    }

    /**
     * The stretch of a season the values cover.
     *
     * @param priorSeasonFinal true when these are last season's final
     *        figures, read before the current season has a played week
     * @param firstWeek the earliest week any value draws on
     * @param lastWeek the latest week any value draws on
     */
    public record Weeks(String season, boolean priorSeasonFinal, int firstWeek, int lastWeek) {
    }

    /**
     * Who a rank runs over.
     *
     * @param group who is ranked, plural, such as "tight ends" or "defenses"
     * @param size how many are ranked
     * @param minimumSample the smallest sample ranked
     * @param sampleUnit what the sample is counted in
     */
    public record Population(String group, int size, int minimumSample, String sampleUnit) {

        /**
         * Returns the population in words, such as "of 38 tight ends with
         * at least 10 targets", or "of 32 defenses" when one of anything
         * is enough.
         */
        public String describe() {
            String of = "of %d %s".formatted(size, group);
            return minimumSample <= 1
                    ? of
                    : "%s with at least %d %s".formatted(of, minimumSample, sampleUnit);
        }
    }

    /**
     * Ranks the measurements. A missing or non-finite value is
     * {@link Reason#MISSING}; a sample under the stat's minimum is
     * {@link Reason#BELOW_MINIMUM}. Neither counts toward the population.
     */
    public static Ranking rank(RankedStat stat, Position position, Weeks weeks,
            List<Measurement> measurements) {
        return rank(stat, position, group(stat, position), weeks, measurements);
    }

    /**
     * Ranks players of every position together, the same way
     * {@link #rank} ranks one position.
     */
    public static Ranking rankAll(PlayerStat stat, Weeks weeks, List<Measurement> measurements) {
        return rank(stat, null, "players", weeks, measurements);
    }

    private static Ranking rank(RankedStat stat, Position position, String group, Weeks weeks,
            List<Measurement> measurements) {
        List<Measurement> eligible = new ArrayList<>();
        List<Unranked> unranked = new ArrayList<>();
        for (Measurement measurement : measurements) {
            if (measurement.value() == null || !Double.isFinite(measurement.value())) {
                unranked.add(unranked(measurement, Reason.MISSING));
            } else if (measurement.sample() < stat.minimumSample()) {
                unranked.add(unranked(measurement, Reason.BELOW_MINIMUM));
            } else {
                eligible.add(measurement);
            }
        }
        Comparator<Measurement> byValue = Comparator.comparingDouble(Measurement::value);
        eligible.sort((stat.highestRanksFirst() ? byValue.reversed() : byValue)
                .thenComparing(Measurement::id));
        unranked.sort(Comparator.comparing(Unranked::id));
        return new Ranking(stat, position, weeks,
                new Population(group, eligible.size(), stat.minimumSample(),
                        stat.sampleUnit()),
                placements(eligible), unranked, List.of());
    }

    /** Returns this ranking with the source's names the register cannot place. */
    public Ranking withUnmatched(List<String> names) {
        return new Ranking(stat, position, weeks, population, ranked, unranked,
                names.stream().distinct().sorted().toList());
    }

    /** Returns the entry's place, or empty when it is unranked or was never measured. */
    public Optional<Placement> placement(String id) {
        return ranked.stream().filter(placement -> placement.id().equals(id)).findFirst();
    }

    /**
     * Returns what the place means for the fantasy player: red for a
     * tough defense, a hard remaining schedule or a stat that hurts him,
     * green for an easy defense or schedule or a stat that helps him.
     */
    public Tone tone(Placement placement) {
        boolean firstIsGood = switch (stat) {
            case DefenseStat _, ScheduleStat _ -> false;
            case PlayerStat player -> player.direction() == Direction.HIGHER_IS_BETTER;
        };
        return Tone.of(placement.rank(), placement.rankFromBottom(), population.size(),
                firstIsGood);
    }

    private static List<Placement> placements(List<Measurement> inOrder) {
        List<Placement> placements = new ArrayList<>();
        int size = inOrder.size();
        int rank = 1;
        for (int index = 0; index < size; index++) {
            Measurement measurement = inOrder.get(index);
            if (index > 0 && !measurement.value().equals(inOrder.get(index - 1).value())) {
                rank = index + 1;
            }
            int lastTied = index;
            while (lastTied + 1 < size
                    && inOrder.get(lastTied + 1).value().equals(measurement.value())) {
                lastTied++;
            }
            placements.add(new Placement(measurement.id(), measurement.name(),
                    measurement.value(), measurement.sample(), rank, size - lastTied));
        }
        return placements;
    }

    private static Unranked unranked(Measurement measurement, Reason reason) {
        return new Unranked(measurement.id(), measurement.name(), reason, measurement.sample());
    }

    private static String group(RankedStat stat, Position position) {
        return switch (stat) {
            case DefenseStat _ -> "defenses";
            case ScheduleStat _ -> "teams";
            case PlayerStat _ -> position.plural();
        };
    }
}
