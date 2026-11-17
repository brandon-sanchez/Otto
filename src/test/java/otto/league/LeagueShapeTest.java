package otto.league;

import java.util.Map;

import org.junit.jupiter.api.Test;

import otto.lineup.PositionCutoffs;

import static org.assertj.core.api.Assertions.assertThat;

class LeagueShapeTest {

    private static final PositionCutoffs FALLBACK = new PositionCutoffs(24, 24, 24, 12);

    @Test
    void aPositionTheLineupRanksReadsItsStarters() {
        LeagueShape shape = new LeagueShape(12, Map.of("RB", 2, "TE", 1),
                Map.of("RB", 30, "TE", 13), FALLBACK);

        assertThat(shape.replacementRank("RB")).isEqualTo(30);
        assertThat(shape.replacementRank("TE")).isEqualTo(13);
    }

    @Test
    void aPositionWithNoSlotReadsTheConfiguredCutoff() {
        LeagueShape shape = new LeagueShape(12, Map.of("RB", 2), Map.of(), FALLBACK);

        assertThat(shape.replacementRank("TE")).isEqualTo(12);
        assertThat(shape.hasReplacementLevel("TE")).isTrue();
    }

    @Test
    void aPositionTheCutoffsDoNotNameHasNoReplacementLevel() {
        LeagueShape shape = new LeagueShape(12, Map.of("RB", 2), Map.of(), FALLBACK);

        assertThat(shape.replacementRank("K")).isZero();
        assertThat(shape.hasReplacementLevel("K")).isFalse();
    }
}
