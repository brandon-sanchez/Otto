package otto.ceiling;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import otto.lineup.LeagueScoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Expected values come from outside the code: the buy-low and sell-high
 * research note's tables (weekly PPR scores at rank cutoffs, 2019-2025,
 * and boom rate by points per game), and a gamma distribution computed
 * with SciPy for the worked examples.
 */
class CeilingTest {

    /**
     * How far a modelled boom rate may sit from the research table, in
     * percentage points. The table pools RB, WR and TE seasons by points
     * per game and the model is fitted per position, so the two agree
     * only to within a few points.
     */
    private static final double TABLE_TOLERANCE = 5.0;

    private static final Map<String, Double> PPR = Map.ofEntries(
            Map.entry("pass_yd", 0.04), Map.entry("pass_td", 4.0), Map.entry("pass_int", -2.0),
            Map.entry("pass_2pt", 2.0), Map.entry("rush_yd", 0.1), Map.entry("rush_td", 6.0),
            Map.entry("rush_2pt", 2.0), Map.entry("rec", 1.0), Map.entry("rec_yd", 0.1),
            Map.entry("rec_td", 6.0), Map.entry("rec_2pt", 2.0), Map.entry("fum_lost", -2.0));

    /** Catches in the average WR12 week, 2019-2025. */
    private static final double WR12_CATCHES = 6.2;

    /** Catches in the average TE6 week, 2019-2025. */
    private static final double TE6_CATCHES = 5.3;

    /** Touchdown passes in the average QB6 week, 2019-2025. */
    private static final double QB6_TOUCHDOWN_PASSES = 2.4;

    private static LeagueScoring scoring(Map<String, Double> changes) {
        Map<String, Double> settings = new HashMap<>(PPR);
        settings.putAll(changes);
        return new LeagueScoring(settings);
    }

    /**
     * The research table's points-per-game bands, at their midpoints;
     * the open 22+ band is read at 24.
     */
    @ParameterizedTest
    @CsvSource({
            "RB, 11.5, 13, 4.5", "RB, 14.5, 23, 10", "RB, 17.5, 34, 19",
            "RB, 20.5, 47, 29.5", "RB, 24, 62, 45",
            "WR, 11.5, 13, 4.5", "WR, 14.5, 23, 10", "WR, 17.5, 34, 19",
            "WR, 20.5, 47, 29.5", "WR, 24, 62, 45",
            "TE, 11.5, 13, 4.5", "TE, 14.5, 23, 10", "TE, 17.5, 34, 19",
            "TE, 20.5, 47, 29.5", "TE, 24, 62, 45"})
    void boomRatesMatchTheResearchTableWithinFivePoints(String position, double pointsPerGame,
            double twentyPlusPercent, double twentyFivePlusPercent) {
        WeeklyRange range = WeeklyRange.around(position, pointsPerGame).orElseThrow();

        assertThat(100 * range.chanceOfAtLeast(20))
                .isCloseTo(twentyPlusPercent, within(TABLE_TOLERANCE));
        assertThat(100 * range.chanceOfAtLeast(25))
                .isCloseTo(twentyFivePlusPercent, within(TABLE_TOLERANCE));
    }

    @ParameterizedTest
    @CsvSource({"QB, 23.5", "RB, 17.4", "WR, 19.5", "TE, 14.6"})
    void inFullPprTheBoomLinesAreTheResearchCutoffs(String position, double cutoff) {
        assertThat(BoomLine.of(position, scoring(Map.of())).orElseThrow().points())
                .isCloseTo(cutoff, within(0.2));
    }

    @Test
    void halfPprLowersAReceiverLineByHalfHisCatches() {
        assertThat(BoomLine.of("WR", scoring(Map.of("rec", 0.5))).orElseThrow().points())
                .isCloseTo(19.5 - 0.5 * WR12_CATCHES, within(0.2));
    }

    @Test
    void aTightEndPremiumRaisesTheTightEndLine() {
        assertThat(BoomLine.of("TE", scoring(Map.of("bonus_rec_te", 0.5))).orElseThrow()
                .points()).isCloseTo(14.6 + 0.5 * TE6_CATCHES, within(0.2));
    }

    @Test
    void sixPointPassingTouchdownsRaiseTheQuarterbackLine() {
        assertThat(BoomLine.of("QB", scoring(Map.of("pass_td", 6.0))).orElseThrow().points())
                .isCloseTo(23.5 + 2 * QB6_TOUCHDOWN_PASSES, within(0.2));
    }

    @Test
    void aRunningBackProjectedForSixteenAndAHalfHasARightSkewedRange() {
        Ceiling ceiling = Ceiling.estimate("RB", 16.5, scoring(Map.of())).orElseThrow();

        assertThat(ceiling.floor()).isCloseTo(7.1, within(0.1));
        assertThat(ceiling.typicalLow()).isCloseTo(10.4, within(0.1));
        assertThat(ceiling.typicalHigh()).isCloseTo(21.1, within(0.1));
        assertThat(ceiling.bigWeek()).isCloseTo(27.7, within(0.1));
        assertThat(ceiling.bigWeek() - 16.5).isGreaterThan(16.5 - ceiling.floor());
        assertThat(100 * ceiling.boomChance()).isCloseTo(39.3, within(0.5));
    }

    @Test
    void aHigherProjectionBoomsMoreOften() {
        double steady = Ceiling.estimate("WR", 12, scoring(Map.of())).orElseThrow().boomChance();
        double star = Ceiling.estimate("WR", 18, scoring(Map.of())).orElseThrow().boomChance();

        assertThat(star).isGreaterThan(steady);
    }

    @Test
    void noEstimateForAPositionWithoutABoomLineOrForNoProjection() {
        assertThat(Ceiling.estimate("K", 9, scoring(Map.of()))).isEmpty();
        assertThat(Ceiling.estimate("WR", 0, scoring(Map.of()))).isEmpty();
    }
}
