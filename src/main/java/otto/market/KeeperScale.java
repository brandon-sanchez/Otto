package otto.market;

import java.util.List;

/**
 * What draft picks, auction dollars and a keeper spot are worth in
 * dynasty value points, read off one league's dynasty market.
 *
 * @param keeperLine the dynasty value of the best rostered player who
 *        falls outside every team's likely keepers, which is what a
 *        keeper spot gives up when keepers cost nothing else
 * @param players every player's dynasty value on the market, highest
 *        first; draft picks are left out
 */
public record KeeperScale(int keeperLine, List<Integer> players) {

    public KeeperScale {
        players = List.copyOf(players);
    }

    /**
     * Returns what a pick in this round is worth: the dynasty value of
     * the player the market ranks at the middle of that round, or 0 when
     * the market prices nobody that deep.
     */
    public int pick(int round, int teams) {
        int index = (round - 1) * teams + (teams + 1) / 2 - 1;
        return index < players.size() ? players.get(index) : 0;
    }

    /**
     * Returns what this many auction dollars are worth. The league's
     * dollars buy its drafted players, so a dollar is worth their
     * combined dynasty value over every team's budget.
     *
     * @param budget each team's auction budget in dollars
     * @param drafted how many players the auction fills, every team's
     *        rounds together
     */
    public int dollars(int price, int budget, int teams, int drafted) {
        long pool = players.stream().limit(drafted).mapToLong(Integer::longValue).sum();
        return (int) Math.round((double) price * pool / ((long) budget * teams));
    }
}
