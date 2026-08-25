package otto.trade;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import otto.lineup.LineupOptimizer;
import otto.lineup.Slot;

import static org.assertj.core.api.Assertions.assertThat;

class RosterFitTest {

    @Test
    void aRealPlayerWinsATieWithAReplacementFiller() {
        RosterFit fit = new RosterFit(new LineupOptimizer());
        RosterFit.Pool pool = new RosterFit.Pool(
                Map.of("zzzz-real-player", 10.0),
                Map.of("zzzz-real-player", "RB"),
                Map.of("RB", 10.0));

        RosterFit.Factor factor = fit.of(
                List.of(new Slot("RB", Set.of("RB"))), pool, "zzzz-real-player");

        assertThat(factor.factor()).isEqualTo(1.10);
        assertThat(factor.reason()).contains("starts for that roster");
    }
}
