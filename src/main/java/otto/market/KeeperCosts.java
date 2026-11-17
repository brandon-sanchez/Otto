package otto.market;

import otto.sleeper.SleeperAdapter;

/**
 * Where a keeper league's cost of keeping a player comes from. A
 * keeper's bonus is his dynasty value minus this cost, so how the
 * league prices keepers plugs in here and nowhere else.
 */
public interface KeeperCosts {

    /**
     * Returns how the league prices its keepers, read once for one
     * valuation and then asked about each player.
     *
     * <p>Side effects: reads the league's past drafts from Sleeper and
     * its stored keeper rule.
     */
    Pricing pricing(SleeperAdapter.League league);

    /**
     * Records that an answer has asked the owner how his league prices
     * keepers, so later answers state the assumption without asking
     * again.
     *
     * <p>Side effects: writes the stored keeper rules.
     */
    void asked(String leagueId);

    /** One league's keeper prices. */
    @FunctionalInterface
    interface Pricing {

        /**
         * Returns what keeping the player costs his team next season.
         *
         * @param scale what draft picks, auction dollars and a keeper
         *        spot are worth in dynasty value points
         */
        KeeperCost costOf(String playerId, KeeperScale scale);
    }
}
