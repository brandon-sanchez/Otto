package otto.team;

import java.util.List;

/** One move that would shore up a thin position on the user's team. */
public sealed interface Suggestion {

    /**
     * Claim a free agent at a thin position.
     *
     * @param need the user's thin position the free agent plays
     * @param add the free agent, best per-game average there
     * @param place where he would sit among the user's players at that
     *        position, 1 for the best
     */
    record Waiver(PositionProfile need, RatedPlayer add, int place) implements Suggestion {
    }

    /**
     * Trade from a surplus position for help at a thin one.
     *
     * @param give the user's startable players beyond the spots that can
     *        start them, best first
     * @param partners the managers deeper than the user at the thin
     *        position and thin at the surplus one
     */
    record Trade(PositionProfile surplus, PositionProfile need, List<RatedPlayer> give,
            List<String> partners) implements Suggestion {

        public Trade {
            give = List.copyOf(give);
            partners = List.copyOf(partners);
        }
    }
}
