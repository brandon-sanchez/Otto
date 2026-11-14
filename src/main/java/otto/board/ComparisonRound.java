package otto.board;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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
     * One pickup a strategy proposed, as it stood when the round was
     * decided.
     *
     * @param position blank when the player directory did not know him
     * @param addable he was on no roster in the league
     * @param legal one of the league's starting slots takes his position
     */
    record Pick(String playerId, String name, String position, boolean addable,
            boolean legal) {
    }
}
