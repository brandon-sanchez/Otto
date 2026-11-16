package otto.market;

import java.time.LocalDate;
import java.util.Optional;

/**
 * What one player or draft pick is worth on the trade market in one
 * league, in that league's format.
 *
 * @param id the Sleeper player id, or the pick in plain words
 * @param name the player's name, or the pick in plain words
 * @param league the league's format in a manager's words, such as
 *        "12-team Superflex PPR keeper"
 * @param value what the league's format makes him worth: redraft value
 *        in redraft, dynasty value in dynasty, and in keeper his
 *        redraft value plus {@link Keeper#bonus()} when he is a likely
 *        keeper
 * @param source the source {@code value} comes from; in keeper, the
 *        source of the redraft value
 * @param asOf the day that source's list was read
 * @param ageDays how many days old that list is, 0 for today's
 * @param listing his row on FantasyCalc's list for this format, with its
 *        ranks, tier and trend; empty when the value came from
 *        DynastyProcess
 * @param history how his value moved over Otto's own daily history of
 *        that list, across up to {@link MarketValues#LONGEST_WINDOW_DAYS}
 *        days; empty until Otto holds an earlier day
 * @param keeper the keeper blend, present only in a keeper league
 */
public record MarketQuote(
        String id,
        String name,
        String league,
        int value,
        Source source,
        LocalDate asOf,
        long ageDays,
        Optional<MarketPlayer> listing,
        Optional<MarketValues.Change> history,
        Optional<Keeper> keeper) {

    /** Where a value comes from, named as the answer names it. */
    public enum Source {
        FANTASY_CALC("FantasyCalc"),
        DYNASTY_PROCESS("DynastyProcess");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * A keeper league's blend for one player. The keeper line is the
     * dynasty value of the best rostered player who falls outside every
     * team's likely keepers: what a keeper spot gives up when keepers
     * cost nothing else.
     *
     * @param keeperLimit the most players a team may keep, empty when the
     *        league does not say, in which case nobody is a likely keeper
     * @param likely true when he is one of his team's likely keepers:
     *        its players worth more by dynasty value than keeping them
     *        costs, the most valuable first, up to the keeper limit
     * @param dynastyValue his dynasty value, empty when neither source
     *        prices him
     * @param dynastySource where {@code dynastyValue} comes from
     * @param bonus his dynasty value minus his cost from
     *        {@link KeeperCosts}, 0 when he is not a likely keeper
     * @param cost what keeping him costs next season, empty when nobody
     *        is counted as a likely keeper or no source prices him
     */
    public record Keeper(
            Optional<Integer> keeperLimit,
            boolean likely,
            int redraftValue,
            Optional<Integer> dynastyValue,
            Optional<Source> dynastySource,
            int keeperLine,
            int bonus,
            Optional<KeeperCost> cost) {
    }
}
