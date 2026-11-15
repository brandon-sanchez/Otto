package otto.advice;

import java.util.Optional;

/**
 * One complete roster plan for a player decision that code verified as
 * legal in the league: hold him, move him to or off IR, drop someone
 * else, or drop him.
 *
 * @param id what the answer names to choose this plan
 * @param summary the plan in plain words, without numbers
 * @param drop the player the plan drops, empty when it drops nobody
 * @param add the free agent the plan adds, empty when it adds nobody
 */
public record RosterPlan(String id, String summary, Optional<String> drop, Optional<String> add) {
}
