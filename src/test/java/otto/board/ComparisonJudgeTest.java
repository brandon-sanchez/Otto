package otto.board;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import otto.lineup.LineupOptimizer;

import static org.assertj.core.api.Assertions.assertThat;

class ComparisonJudgeTest {

    private static final Instant DEADLINE = Instant.parse("2026-09-16T07:00:00Z");

    private static final Map<String, Double> ACTUAL = Map.of(
            "starter", 10.0, "bench", 5.0, "pickup", 8.0);

    private static final Map<String, String> FULL_ROSTER = Map.of("starter", "RB", "bench", "RB");

    private static WeeklyComparison.PickResult judged(Map<String, String> lineupPool,
            Optional<String> drop) {
        ComparisonRound round = new ComparisonRound("2026", 2, DEADLINE, "board-run", Map.of(),
                List.of("RB"), lineupPool, Map.of(), Map.of());
        ComparisonJudge judge = new ComparisonJudge(round, new LineupOptimizer(),
                (playerId, position) -> Optional.ofNullable(ACTUAL.get(playerId)));
        ComparisonRound.Pick pick = new ComparisonRound.Pick("pickup", "Pickup", "RB", true, true,
                drop);
        WeeklyComparison comparison = judge.judge("baselines-run",
                Map.of(BoardStrategy.EVIDENCE_BOARD, List.of(pick)), Map.of(), DEADLINE,
                DEADLINE);
        return comparison.strategies().stream()
                .filter(result -> result.strategy() == BoardStrategy.EVIDENCE_BOARD)
                .findFirst().orElseThrow()
                .picks().getFirst();
    }

    @Test
    void aPickupThatDropsTheStarterIsScoredOnTheLineupWithoutHim() {
        assertThat(judged(FULL_ROSTER, Optional.of("starter")).lineupGain()).contains(-2.0);
    }

    @Test
    void aPickupThatDropsABenchPlayerKeepsTheStartersPoints() {
        assertThat(judged(FULL_ROSTER, Optional.of("bench")).lineupGain()).contains(0.0);
    }

    @Test
    void aPickupIntoAnOpenSpotDropsNobody() {
        assertThat(judged(Map.of("bench", "RB"), Optional.empty()).lineupGain()).contains(3.0);
    }

    @Test
    void aFullRostersBaselineDropsItsLowestProjectedActivePlayer() {
        Map<String, String> pool = Map.of("starter", "RB", "bench", "RB", "unprojected", "WR");

        assertThat(ComparisonRound.baselineDrop(pool, 0,
                Map.of("starter", 14.0, "bench", 6.0, "unprojected", 7.5)))
                .contains("bench");
        assertThat(ComparisonRound.baselineDrop(pool, 0, Map.of("starter", 14.0, "bench", 6.0)))
                .contains("unprojected");
        assertThat(ComparisonRound.baselineDrop(pool, 1, Map.of("bench", 6.0))).isEmpty();
    }
}
