package otto.board;

import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import otto.lineup.Slot;

/**
 * What one claim round's comparison needs from decision time. It is
 * kept in the baselines' Decision Record, so the round is judged after
 * the games against what was known when it was decided.
 *
 * @param boardRunId the run id the evidence-based board records this round under
 * @param scoring the league's scoring settings, by Sleeper stat key
 * @param startingSlots the league's starting slots, in lineup order
 * @param lineupPool every player on the user's roster who could start,
 *        IR and taxi excluded, with his position, by player id
 * @param picks each baseline's pickups, best first; the evidence-based board's
 *        are read from its own record when the round is judged
 * @param unavailable the reason for each baseline that could not be
 *        built this round
 */
record ComparisonRound(
        String season,
        int week,
        Instant claimDeadline,
        String boardRunId,
        Map<String, Double> scoring,
        List<String> startingSlots,
        Map<String, String> lineupPool,
        Map<BoardStrategy, List<Pick>> picks,
        Map<BoardStrategy, String> unavailable) {

    ComparisonRound {
        scoring = Map.copyOf(scoring);
        startingSlots = List.copyOf(startingSlots);
        lineupPool = Map.copyOf(lineupPool);
        picks = ordered(picks);
        unavailable = ordered(unavailable);
    }

    private static <V> Map<BoardStrategy, V> ordered(Map<BoardStrategy, V> byStrategy) {
        return byStrategy.isEmpty() ? Map.of()
                : Collections.unmodifiableMap(new EnumMap<>(byStrategy));
    }

    /**
     * Returns the player a baseline's pickup drops: none while the
     * roster has an open spot, and otherwise the active player with the
     * lowest projection for the week, an unprojected player lowest of
     * all, ties broken by player id.
     *
     * @param lineupPool the user's active players with their positions,
     *        by player id
     * @param openSpots how many active roster spots are free
     * @param projected each player's projected points for the week, by
     *        player id; a player missing from it has no projection
     */
    static Optional<String> baselineDrop(Map<String, String> lineupPool, int openSpots,
            Map<String, Double> projected) {
        if (openSpots > 0) {
            return Optional.empty();
        }
        return lineupPool.keySet().stream()
                .min(Comparator.comparingDouble((String playerId) ->
                                projected.getOrDefault(playerId, Double.NEGATIVE_INFINITY))
                        .thenComparing(Comparator.naturalOrder()));
    }

    /**
     * One pickup a strategy proposed, as it stood when the round was
     * decided.
     *
     * @param position blank when the player directory did not know him
     * @param addable he was on no roster in the league
     * @param legal one of the league's starting slots takes his position
     * @param drop the rostered player the claim gives up: the board's
     *        named drop, or for a baseline the one
     *        {@link #baselineDrop} picks; empty for an open spot, and
     *        read as empty from a round stored without it
     */
    record Pick(String playerId, String name, String position, boolean addable,
            boolean legal, Optional<String> drop) {

        Pick {
            drop = drop == null ? Optional.empty() : drop;
        }

        /**
         * Returns a pick that drops no one, legal when one of the
         * league's starting slots takes his position.
         *
         * @param addable whether he was a free agent the strategy could
         *        claim when the round was decided
         */
        static Pick of(String playerId, String name, String position, boolean addable,
                List<Slot> startingSlots) {
            return new Pick(playerId, name, position, addable,
                    startingSlots.stream().anyMatch(slot -> slot.accepts(position)),
                    Optional.empty());
        }

        Pick withDrop(Optional<String> dropped) {
            return new Pick(playerId, name, position, addable, legal, dropped);
        }
    }
}
