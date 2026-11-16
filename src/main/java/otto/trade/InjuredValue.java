package otto.trade;

import java.util.Optional;

import otto.sleeper.LeagueRules;

/**
 * What an injured player is worth to the user in his league's format.
 *
 * <ul>
 *   <li>Redraft: only the points of the games he likely plays after he
 *       returns, before the league's playoffs end. An injury that ends
 *       his season makes him worth 0.
 *   <li>Keeper: his redraft market value, which the market already
 *       lowers for his injury and which is 0 when it ends his season,
 *       plus his keeper value.
 *   <li>Dynasty: his long-term market value.
 * </ul>
 *
 * @param player his road back this season
 * @param holder the manager whose team he is on; empty for a free agent
 *        or one of the user's own players
 * @param redraftMarket his redraft market value, in a keeper league
 * @param keeperValue his dynasty value less what keeping him costs, 0
 *        when he isn't a likely keeper; in a keeper league only
 * @param dynastyValue his dynasty market value, in a dynasty league
 * @param timing why the timing suits both sides, in one sentence; empty
 *        when his worth doesn't depend on timing or nobody holds him
 */
public record InjuredValue(
        InjuredPlayer player,
        LeagueRules.LeagueFormat format,
        Optional<String> holder,
        Optional<Integer> redraftMarket,
        Optional<Integer> keeperValue,
        Optional<Integer> dynastyValue,
        Optional<String> timing) {

    /**
     * Returns his worth on the measure that decides his league: points in
     * redraft, market value in keeper and dynasty.
     *
     * @return empty when it can't be told, such as a redraft player whose
     *         news gives no timeline
     */
    public Optional<Double> worth() {
        return switch (format) {
            case REDRAFT -> player.pointsLeft();
            case KEEPER -> redraftMarket.flatMap(redraft -> keeperValue
                    .map(keeper -> (double) redraft + keeper));
            case DYNASTY -> dynastyValue.map(Integer::doubleValue);
        };
    }

    /**
     * Returns what he scores a game once he is back, which his weeks out
     * don't drag down; empty when nothing projects him.
     */
    public Optional<Double> perGameWhenBack() {
        return player.perGame();
    }

    /** True when he is worth something to the user, so a trade for him can make sense. */
    public boolean worthBuying() {
        return worth().filter(value -> value > 0).isPresent();
    }
}
