package otto.market;

/**
 * Where a keeper league's cost of keeping one player comes from. A
 * keeper's bonus is his dynasty value minus this cost, so a source of
 * real keeper costs, such as the draft round a keeper uses up, plugs in
 * here and nowhere else.
 */
public interface KeeperCosts {

    /**
     * Returns what keeping the player costs his team, in the same dynasty
     * value points as his own value.
     *
     * @param keeperLine the dynasty value of the best rostered player who
     *        falls outside every team's likely keepers, which is what a
     *        keeper spot gives up
     */
    int costOf(String playerId, int keeperLine);
}
