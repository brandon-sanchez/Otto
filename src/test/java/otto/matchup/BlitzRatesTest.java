package otto.matchup;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import otto.nflverse.Coverage;
import otto.nflverse.FtnCharting;
import otto.nflverse.PlayByPlay;
import otto.nflverse.Players;

import static org.assertj.core.api.Assertions.assertThat;

class BlitzRatesTest {

    private static final Instant AT = Instant.parse("2026-10-01T12:00:00Z");
    private static final Coverage NONE = new Coverage(1, List.of());

    private static PlayByPlay.Dropback pass(String gameId, String playId, int week) {
        return new PlayByPlay.Dropback(gameId, playId, week, "LAC", "SEA", "00-0036355",
                false);
    }

    @Test
    void theWeeksAreTheWeeksOfTheMatchedDropbacksOnly() {
        PlayByPlay playByPlay = new PlayByPlay("2026", false, AT, AT, NONE, List.of(), List.of(
                pass("2026_03_LAC_SEA", "10", 3), pass("2026_04_LAC_SEA", "20", 4),
                pass("2026_05_LAC_SEA", "30", 5)));
        FtnCharting charting = new FtnCharting("2026", AT, AT, NONE, List.of(), List.of(
                new FtnCharting.Dropback("2026_03_LAC_SEA", "10", 1),
                new FtnCharting.Dropback("2026_04_LAC_SEA", "20", 0)));

        BlitzRates rates = BlitzRates.of(playByPlay, charting,
                new Players("2026", AT, AT, NONE, List.of())).orElseThrow();

        assertThat(rates.weeks()).containsExactly(3, 4);
    }
}
