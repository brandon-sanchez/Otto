package otto.board;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * How one claim round's boards turned out: what each strategy's
 * pickups would have added to the user's lineup in the week they were
 * claimed for. Stored as the outcome record of the round's baselines.
 *
 * <p>A pickup's worth is its lineup gain: the points the user's best
 * lineup that week would have scored with him on the roster, less what
 * it scored without him, both in league scoring and from actual
 * points. A pickup who would not have started gains nothing, and no
 * pickup gains nothing by definition. Only players who were addable
 * and legal when the round was decided are counted.
 *
 * @param baselinesRunId the run id of the baselines' Decision Record
 *        this outcome judges
 * @param boardRunId the run id the evidence-based board records this round under
 * @param week the week whose games the pickups were claimed for
 * @param statsAsOf the version of the week's stats the points come from
 * @param decisions how many strategies the round compares
 * @param decisionsCovered how many of them made a decision that could
 *        be judged
 * @param unpricedLineup the user's players whose points are unknown,
 *        by player id; they are left out of both lineups, so a gain
 *        over a slot only they could fill reads larger than it was
 * @param strategies one result per strategy, in {@link BoardStrategy} order
 */
public record WeeklyComparison(
        String baselinesRunId,
        String boardRunId,
        String season,
        int week,
        Instant claimDeadline,
        Instant statsAsOf,
        Instant judgedAt,
        int decisions,
        int decisionsCovered,
        List<String> unpricedLineup,
        List<StrategyResult> strategies) {

    public WeeklyComparison {
        unpricedLineup = List.copyOf(unpricedLineup);
        strategies = List.copyOf(strategies);
    }

    /**
     * One strategy's decision and what it was worth.
     *
     * @param covered true when the strategy made a decision and, unless
     *        it proposed nobody, at least one of its pickups is counted
     * @param note why the decision is not covered, or what limits it
     * @param proposed how many pickups the strategy proposed
     * @param counted how many of them are counted
     * @param leadGain the lineup gain of its best-ranked counted pickup,
     *        the claim a manager following it would place first; 0 when
     *        it proposed nobody, empty when not covered
     * @param meanGain the mean lineup gain over its counted pickups; 0
     *        when it proposed nobody, empty when not covered
     */
    public record StrategyResult(
            BoardStrategy strategy,
            boolean covered,
            Optional<String> note,
            int proposed,
            int counted,
            Optional<Double> leadGain,
            Optional<Double> meanGain,
            List<PickResult> picks) {

        public StrategyResult {
            picks = List.copyOf(picks);
        }
    }

    /**
     * One proposed pickup and what he was worth.
     *
     * @param addable he was on no roster in the league at decision time
     * @param legal one of the league's starting slots takes his position
     * @param points his actual points that week in league scoring; empty
     *        when they are unknown, 0 when a complete week holds no stat
     *        line for him
     * @param lineupGain empty when he is not counted
     * @param excluded why he is not counted, empty when he is
     */
    public record PickResult(
            String playerId,
            String name,
            String position,
            boolean addable,
            boolean legal,
            Optional<Double> points,
            Optional<Double> lineupGain,
            Optional<String> excluded) {
    }
}
