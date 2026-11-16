package otto.market;

import java.util.Optional;

/**
 * What keeping one player costs his team next season, in dynasty value
 * points and in the manager's words.
 *
 * @param value what the pick, dollars or keeper spot he uses up is
 *        worth, in the same dynasty value points as his own value
 * @param keepable false when he has been kept as many seasons as the
 *        league allows, so he can't be kept again
 * @param cost what keeping him costs and why, in a sentence
 * @param rule where the league's keeper rule comes from, in a sentence:
 *        the owner's own rule, one read from the league's drafts, or the
 *        assumption Otto makes without one
 * @param question the question that asks the owner for his league's
 *        rule; empty once the rule is set or an answer has asked
 * @param budget each team's auction budget in dollars, empty in a
 *        league that drafts in rounds
 */
public record KeeperCost(int value, boolean keepable, String cost, String rule,
        Optional<String> question, Optional<Integer> budget) {
}
