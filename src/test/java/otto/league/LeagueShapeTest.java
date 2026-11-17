package otto.league;

import java.util.Map;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import otto.lineup.PositionCutoffs;

import static org.assertj.core.api.Assertions.assertThat;

class LeagueShapeTest {

    private static final PositionCutoffs FALLBACK = new PositionCutoffs(24, 24, 24, 12);

    @Test
    void aPositionTheLineupRanksReadsItsStarters() {
        LeagueShape shape = new LeagueShape(12, Map.of("RB", 2, "TE", 1),
                Map.of("RB", 30, "TE", 13), FALLBACK);

        assertThat(shape.replacementRank("RB")).isEqualTo(OptionalInt.of(30));
        assertThat(shape.replacementRank("TE")).isEqualTo(OptionalInt.of(13));
    }

    @Test
    void aPositionWithNoSlotReadsTheConfiguredCutoff() {
        LeagueShape shape = new LeagueShape(12, Map.of("RB", 2), Map.of(), FALLBACK);

        assertThat(shape.replacementRank("TE")).isEqualTo(OptionalInt.of(12));
        assertThat(shape.hasReplacementLevel("TE")).isTrue();
    }

    @Test
    void aKickerOrDefenseWithNoSlotHasNoReplacementLevel() {
        LeagueShape shape = new LeagueShape(12, Map.of("RB", 2), Map.of(), FALLBACK);

        assertThat(shape.replacementRank("K")).isEmpty();
        assertThat(shape.hasReplacementLevel("DEF")).isFalse();
    }

    @Test
    void aKickerAndDefenseRankAtTeamsTimesSlotsWhateverTheLineupsStart() {
        LeagueShape shape = new LeagueShape(10, Map.of("RB", 2, "K", 1, "DEF", 2), Map.of(),
                FALLBACK).withStarters(Map.of("RB", 26, "K", 7, "DEF", 9));

        assertThat(shape.replacementRank("K")).isEqualTo(OptionalInt.of(10));
        assertThat(shape.replacementRank("DEF")).isEqualTo(OptionalInt.of(20));
    }

    @Test
    void aSlottedPositionTheLineupsDidNotCountReadsTeamsTimesSlots() {
        LeagueShape shape = new LeagueShape(12, Map.of("RB", 2, "TE", 1), Map.of(), FALLBACK)
                .withStarters(Map.of("RB", 30));

        assertThat(shape.replacementRank("TE")).isEqualTo(OptionalInt.of(12));
    }
}
