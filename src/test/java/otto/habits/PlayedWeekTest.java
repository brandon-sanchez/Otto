package otto.habits;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlayedWeekTest {

    private static final List<String> SLOTS = List.of("RB", "K", "DEF");
    private static final int KICKER_SLOT = 1;
    private static final int DEFENSE_SLOT = 2;

    @Test
    void aBenchedRunningBackIsNeverCoverForAKickerOrDefenseSlot() {
        PlayedWeek played = week(Map.of("rb", 20.0, "bench-rb", 15.0, "k", 0.0, "KC", 0.0),
                Map.of("rb", "RB", "bench-rb", "RB", "k", "K", "KC", "DEF"));

        assertThat(played.bestBenchFor(KICKER_SLOT)).isEmpty();
        assertThat(played.bestBenchFor(DEFENSE_SLOT)).isEmpty();
    }

    @Test
    void aBenchedKickerOrDefenseCoversItsOwnSlot() {
        PlayedWeek played = week(
                Map.of("rb", 20.0, "k", 0.0, "KC", 0.0, "bench-k", 7.0, "BUF", 9.0),
                Map.of("rb", "RB", "k", "K", "KC", "DEF", "bench-k", "K", "BUF", "DEF"));

        assertThat(played.bestBenchFor(KICKER_SLOT)).contains(7.0);
        assertThat(played.bestBenchFor(DEFENSE_SLOT)).contains(9.0);
    }

    @Test
    void aSlotTypeOttoDoesNotModelHasNoBench() {
        PlayedWeek played = new PlayedWeek(List.of("DL"), List.of("dl"),
                Map.of("dl", 0.0, "bench-rb", 15.0), Map.of(),
                Map.of("dl", "DL", "bench-rb", "RB"), Map.of(), List.of("dl"), Set.of(),
                Map.of(), Map.of());

        assertThat(played.bestBenchFor(0)).isEmpty();
    }

    private static PlayedWeek week(Map<String, Double> points, Map<String, String> positions) {
        List<String> starters = List.of("rb", "k", "KC");
        return new PlayedWeek(SLOTS, starters, points, Map.of(), positions, Map.of(), starters,
                Set.of(), Map.of(), Map.of());
    }
}
