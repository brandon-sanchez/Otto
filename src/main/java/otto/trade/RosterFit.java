package otto.trade;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import otto.lineup.LineupOptimizer;
import otto.lineup.Slot;

/**
 * What a player is worth to one roster rather than in the abstract, and
 * what one roster's starting lineup is worth in total.
 *
 * <p>A running back who walks into a starting slot is worth more to the
 * team that gets him than the same back sat behind two better ones, and
 * a trade that ignores that talks a user into swapping his own bench
 * for somebody else's. Each of the four sides of a trade therefore asks
 * this question of its own roster.
 *
 * <p>The question is answered by the optimal lineup rather than by a
 * count of players at the position, because the flex slots decide it: a
 * fourth receiver starts in a league with two flex slots and does not in
 * a league without them. That is the same optimizer the lineup tools
 * already run, so a trade and a start-sit call cannot disagree about who
 * starts.
 *
 * <p>Every lineup here is filled out with replacement-level players, one
 * per position per starting slot. A manager who loses a starter does not
 * field an empty slot; he claims the best free agent there is, and
 * Replacement Level is what that costs him. Leaving the slot empty would
 * price a lost starter as a total loss and make every trade away look
 * ruinous.
 */
@Component
public class RosterFit {

    /** He walks into the optimal lineup, so he upgrades a starting slot. */
    private static final double STARTER_UPGRADE = 1.10;

    /** Somebody better at his position is already on the bench ahead of him. */
    private static final double BURIED = 0.90;

    /** Neither: the first man off the bench, which is cover worth having. */
    private static final double DEPTH = 1.00;

    /**
     * What a replacement-level filler is called. No Sleeper player id
     * carries a colon, so a filler can never collide with a real player.
     */
    private static final String FILLER = "replacement:";

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
     * One roster as the lineup math reads it: rest-of-season points and
     * a position per player, plus what Replacement Level is worth over
     * the same weeks at each position.
     */
    public record Pool(Map<String, Double> points, Map<String, String> positions,
            Map<String, Double> replacement) {
    }

    /**
     * Prices one player against the roster that would hold him.
     *
     * @param slots the league's starting slots
     * @param pool that roster, the player being priced included
     * @param playerId the player to price
     */
    public Factor of(List<Slot> slots, Pool pool, String playerId) {
        Map<String, Double> candidates = withFillers(slots, pool);
        Map<String, String> positions = positionsOf(slots, pool);
        Map<Integer, String> lineup = optimizer.assign(slots, candidates, positions);
        if (lineup.containsValue(playerId)) {
            return new Factor(STARTER_UPGRADE, "starts for that roster over what it has now");
        }
        String position = pool.positions().get(playerId);
        if (position == null) {
            // Callers put the priced player on the roster first, so this
            // is a player the Player Directory knows no position for.
            return new Factor(DEPTH, "has no position I can read, so he is priced at face "
                    + "value against that roster");
        }
        double his = pool.points().getOrDefault(playerId, 0.0);
        long aheadOnTheBench = pool.points().entrySet().stream()
                .filter(other -> !other.getKey().equals(playerId))
                .filter(other -> Objects.equals(pool.positions().get(other.getKey()), position))
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

    /**
     * What this roster's optimal starting lineup projects over the rest
     * of the season, with any slot its own players cannot fill filled at
     * Replacement Level. The difference between this before a trade and
     * after it is what the trade does to the team's starting lineup.
     */
    public double startingPoints(List<Slot> slots, Pool pool) {
        Map<String, Double> candidates = withFillers(slots, pool);
        Map<String, String> positions = positionsOf(slots, pool);
        return optimizer.assign(slots, candidates, positions).values().stream()
                .mapToDouble(candidates::get)
                .sum();
    }

    /**
     * The roster plus one replacement-level body per position per
     * starting slot, which is enough for a lineup that loses every
     * player it has.
     */
    private static Map<String, Double> withFillers(List<Slot> slots, Pool pool) {
        Map<String, Double> candidates = new LinkedHashMap<>(pool.points());
        pool.replacement().forEach((position, points) -> {
            for (int index = 0; index < slots.size(); index++) {
                candidates.put(FILLER + position + ":" + index, points);
            }
        });
        return candidates;
    }

    private static Map<String, String> positionsOf(List<Slot> slots, Pool pool) {
        Map<String, String> positions = new LinkedHashMap<>(pool.positions());
        pool.replacement().forEach((position, points) -> {
            for (int index = 0; index < slots.size(); index++) {
                positions.put(FILLER + position + ":" + index, position);
            }
        });
        return positions;
    }
}
