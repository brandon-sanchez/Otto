package otto.market;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What draft picks, auction dollars and a keeper spot are worth in
 * dynasty value points, read off one league's dynasty market and its
 * rosters.
 *
 * @param spots what a keeper spot is worth to each rostered player's
 *        team, by player id; a player on no roster is absent
 * @param players every player's dynasty value on the market, highest
 *        first; draft picks are left out
 */
public record KeeperScale(Map<String, KeeperSpot> spots, List<Integer> players) {

    public KeeperScale {
        spots = Map.copyOf(spots);
        players = List.copyOf(players);
    }

    /**
     * What keeping one player gives up on his own team: the keeper spot
     * he takes would otherwise go to the best teammate who'd miss out,
     * the one ranked just past the league's keeper limit among the
     * team's other keepable players.
     *
     * @param usersTeam true when he is on the user's team
     * @param instead the name of the teammate the spot would otherwise
     *        go to; empty when the team has too few other keepable
     *        players of positive value to fill its spots
     * @param value that teammate's dynasty value, and so what the spot
     *        costs; 0 when {@code instead} is empty
     */
    public record KeeperSpot(boolean usersTeam, Optional<String> instead, int value) {
    }

    /** Returns what a keeper spot is worth to the player's team, or empty off any roster. */
    public Optional<KeeperSpot> spotOf(String playerId) {
        return Optional.ofNullable(spots.get(playerId));
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
