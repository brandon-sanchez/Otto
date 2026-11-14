package otto.board;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

import otto.lineup.LineupOptimizer;
import otto.lineup.Slot;

/**
 * Judges one claim round's strategies on actual points: see
 * {@link WeeklyComparison} for what a pickup's lineup gain is and
 * which pickups count.
 */
final class ComparisonJudge {

    private final ComparisonRound round;
    private final LineupOptimizer optimizer;
    private final List<Slot> slots;
    private final Map<String, Double> lineupPoints = new LinkedHashMap<>();
    private final List<String> unpriced = new ArrayList<>();
    private final BiFunction<String, String, Optional<Double>> points;
    private final double withoutPickup;

    /**
     * @param points a player's actual points that week in league
     *        scoring, by player id and position; empty when unknown
     */
    ComparisonJudge(ComparisonRound round, LineupOptimizer optimizer,
            BiFunction<String, String, Optional<Double>> points) {
        this.round = round;
        this.optimizer = optimizer;
        this.slots = Slot.startingSlots(round.startingSlots());
        this.points = points;
        round.lineupPool().keySet().stream().sorted().forEach(playerId ->
                points.apply(playerId, round.lineupPool().get(playerId)).ifPresentOrElse(
                        value -> lineupPoints.put(playerId, value),
                        () -> unpriced.add(playerId)));
        this.withoutPickup = bestLineup(lineupPoints, round.lineupPool());
    }

    /**
     * Judges every strategy.
     *
     * @param picks each strategy's pickups, best first
     * @param unavailable why a strategy made no decision this round
     */
    WeeklyComparison judge(String baselinesRunId,
            Map<BoardStrategy, List<ComparisonRound.Pick>> picks,
            Map<BoardStrategy, String> unavailable, Instant statsAsOf, Instant judgedAt) {
        List<WeeklyComparison.StrategyResult> results = new ArrayList<>();
        for (BoardStrategy strategy : BoardStrategy.values()) {
            String reason = unavailable.get(strategy);
            results.add(reason != null
                    ? new WeeklyComparison.StrategyResult(strategy, false, Optional.of(reason), 0,
                            0, Optional.empty(), Optional.empty(), List.of())
                    : strategy(strategy, picks.getOrDefault(strategy, List.of())));
        }
        return new WeeklyComparison(baselinesRunId, round.boardRunId(), round.season(),
                round.week(), round.claimDeadline(), statsAsOf, judgedAt, results.size(),
                (int) results.stream().filter(WeeklyComparison.StrategyResult::covered).count(),
                unpriced, results);
    }

    private WeeklyComparison.StrategyResult strategy(BoardStrategy strategy,
            List<ComparisonRound.Pick> proposed) {
        List<WeeklyComparison.PickResult> picks = proposed.stream().map(this::pick).toList();
        List<Double> gains = picks.stream()
                .flatMap(pick -> pick.lineupGain().stream())
                .toList();
        if (proposed.isEmpty()) {
            return new WeeklyComparison.StrategyResult(strategy, true, Optional.empty(), 0, 0,
                    Optional.of(0.0), Optional.of(0.0), picks);
        }
        if (gains.isEmpty()) {
            return new WeeklyComparison.StrategyResult(strategy, false,
                    Optional.of("none of its pickups could be counted"), picks.size(), 0,
                    Optional.empty(), Optional.empty(), picks);
        }
        double mean = gains.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        return new WeeklyComparison.StrategyResult(strategy, true, Optional.empty(),
                picks.size(), gains.size(), Optional.of(gains.getFirst()),
                Optional.of(rounded(mean)), picks);
    }

    private WeeklyComparison.PickResult pick(ComparisonRound.Pick pick) {
        Optional<Double> scored = pick.position().isBlank() ? Optional.empty()
                : points.apply(pick.playerId(), pick.position());
        Optional<String> excluded = !pick.addable()
                ? Optional.of("he was on a roster in the league when the round was decided")
                : !pick.legal()
                        ? Optional.of("no starting slot in the league takes his position")
                        : scored.isEmpty()
                                ? Optional.of("his points in week %d are unknown"
                                        .formatted(round.week()))
                                : Optional.empty();
        Optional<Double> gain = excluded.isPresent() ? Optional.empty()
                : Optional.of(rounded(gain(pick, scored.orElseThrow())));
        return new WeeklyComparison.PickResult(pick.playerId(), pick.name(), pick.position(),
                pick.addable(), pick.legal(), scored.map(ComparisonJudge::rounded), gain,
                excluded);
    }

    private double gain(ComparisonRound.Pick pick, double scored) {
        Map<String, Double> withPick = new HashMap<>(lineupPoints);
        withPick.put(pick.playerId(), scored);
        Map<String, String> positions = new HashMap<>(round.lineupPool());
        positions.put(pick.playerId(), pick.position());
        return bestLineup(withPick, positions) - withoutPickup;
    }

    private double bestLineup(Map<String, Double> priced, Map<String, String> positions) {
        return optimizer.assign(slots, priced, positions, Set.of()).values().stream()
                .mapToDouble(priced::get)
                .sum();
    }

    private static double rounded(double points) {
        return Math.round(points * 100) / 100.0;
    }
}
