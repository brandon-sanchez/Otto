package otto.trade;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import otto.lineup.LineupOptimizer;
import otto.lineup.Slot;

/**
 * What a player is worth to one roster rather than in the abstract.
 *
 * <p>A running back who walks into a starting slot is worth more to the
 * team that gets him than the same back sat behind two better ones, and
 * a trade that ignores that talks a user into swapping his own bench
 * for somebody else's.
 *
 * <p>The roster asked is always the one that would receive him, taken
 * after the trade. One player therefore carries one value whichever
 * side of the deal is being read, which is what stops a trade being
 * priced with two different yardsticks.
 *
 * <p>The question is answered by the optimal lineup rather than by a
 * count of players at the position, because the flex slots decide it: a
 * fourth receiver starts in a league with two flexes and does not in a
 * league without them. That is the same optimizer the lineup tools
 * already run, so a trade and a start-sit call cannot disagree about
 * who starts.
 */
@Component
public class RosterFit {

    /** He walks into the optimal lineup, so he upgrades a starting slot. */
    private static final double STARTER_UPGRADE = 1.10;

    /** Somebody better at his position is already on the bench ahead of him. */
    private static final double BURIED = 0.90;

    /** Neither: the first man off the bench, which is cover worth having. */
    private static final double DEPTH = 1.00;

    private final LineupOptimizer optimizer;

    public RosterFit(LineupOptimizer optimizer) {
        this.optimizer = optimizer;
    }

    /**
     * @param factor what the player's value is multiplied by
     * @param reason the roster fact behind it, in the answer's words
     */
    public record Factor(double factor, String reason) {
    }

    /**
     * Prices one player against the roster that holds him.
     *
     * @param slots the league's starting slots
     * @param points rest-of-season points for every player on that
     *        roster, the one being priced included
     * @param positions the position of each of those players
     * @param playerId the player to price
     */
    public Factor of(List<Slot> slots, Map<String, Double> points,
            Map<String, String> positions, String playerId) {
        Map<Integer, String> lineup = optimizer.assign(slots, points, positions);
        if (lineup.containsValue(playerId)) {
            return new Factor(STARTER_UPGRADE, "starts for that roster over what it has now");
        }
        String position = positions.get(playerId);
        double his = points.getOrDefault(playerId, 0.0);
        long aheadOnTheBench = points.entrySet().stream()
                .filter(other -> !other.getKey().equals(playerId))
                .filter(other -> Objects.equals(positions.get(other.getKey()), position))
                .filter(other -> !lineup.containsValue(other.getKey()))
                .filter(other -> other.getValue() > his)
                .count();
        if (aheadOnTheBench > 0) {
            return new Factor(BURIED, "sits behind %d better %s on that bench"
                    .formatted(aheadOnTheBench, position));
        }
        return new Factor(DEPTH, ("is the first %s off that bench, so he is cover rather "
                + "than an upgrade").formatted(position));
    }
}
