package otto.lineup;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class LeagueScoringTest {

    private static final LeagueScoring HALF_PPR_TE_PREMIUM = new LeagueScoring(Map.of(
            "rec", 0.5, "bonus_rec_te", 0.5, "rec_yd", 0.1, "rec_td", 6.0,
            "rush_yd", 0.1, "rush_td", 6.0, "fum_lost", -2.0));

    private static final LeagueScoring FULL_PPR_WITH_BONUSES = new LeagueScoring(Map.of(
            "rec", 1.0, "rec_yd", 0.1, "rush_yd", 0.1, "fum_lost", -2.0,
            "bonus_rec_yd_100", 3.0, "bonus_rush_rec_yd_100", 2.0, "bonus_rec_rb", 0.5));

    private static final LeagueScoring FOUR_POINT_PASSING = new LeagueScoring(Map.of(
            "pass_yd", 0.04, "pass_td", 4.0, "pass_int", -2.0, "rush_yd", 0.1,
            "bonus_pass_yd_300", 2.0, "bonus_pass_cmp_25", 1.0));

    private static double priced(LeagueScoring scoring, Map<String, Double> line,
            String position) {
        return scoring.price(line, position).orElseThrow();
    }

    @Test
    void aTightEndsCatchesEarnHalfAPointAndTheTePremium() {
        Map<String, Double> line = Map.of("rec", 5.0, "rec_yd", 50.0, "rec_td", 1.0);

        assertThat(priced(HALF_PPR_TE_PREMIUM, line, "TE"))
                .isCloseTo(5 * 0.5 + 5 * 0.5 + 50 * 0.1 + 6, within(1e-9));
    }

    @Test
    void theTePremiumCountsOnceWhenSleepersLineAlreadyCarriesIt() {
        Map<String, Double> line = Map.of("rec", 5.0, "rec_yd", 50.0, "rec_td", 1.0,
                "bonus_rec_te", 5.0);

        assertThat(priced(HALF_PPR_TE_PREMIUM, line, "TE"))
                .isCloseTo(5 * 0.5 + 5 * 0.5 + 50 * 0.1 + 6, within(1e-9));
    }

    @Test
    void aWideReceiverGetsNoTePremium() {
        Map<String, Double> line = Map.of("rec", 5.0, "rec_yd", 50.0, "rec_td", 1.0);

        assertThat(priced(HALF_PPR_TE_PREMIUM, line, "WR"))
                .isCloseTo(5 * 0.5 + 50 * 0.1 + 6, within(1e-9));
    }

    @Test
    void aHundredYardGameEarnsTheBonusesSleepersLineFlags() {
        Map<String, Double> line = Map.of("rec", 8.0, "rec_yd", 112.0, "rush_yd", 5.0,
                "fum_lost", 1.0, "bonus_rec_yd_100", 1.0, "bonus_rush_rec_yd_100", 1.0);

        assertThat(priced(FULL_PPR_WITH_BONUSES, line, "WR"))
                .isCloseTo(8 + 112 * 0.1 + 5 * 0.1 - 2 + 3 + 2, within(1e-9));
    }

    @Test
    void aRunningBacksCatchesEarnTheRbPremiumOnce() {
        Map<String, Double> line = Map.of("rec", 4.0, "rec_yd", 45.0, "rush_yd", 60.0,
                "bonus_rush_rec_yd_100", 1.0);
        Map<String, Double> premiumFlagged = Map.of("rec", 4.0, "rec_yd", 45.0,
                "rush_yd", 60.0, "bonus_rush_rec_yd_100", 1.0, "bonus_rec_rb", 4.0);
        double expected = 4 + 4 * 0.5 + 60 * 0.1 + 45 * 0.1 + 2;

        assertThat(priced(FULL_PPR_WITH_BONUSES, line, "RB")).isCloseTo(expected, within(1e-9));
        assertThat(priced(FULL_PPR_WITH_BONUSES, premiumFlagged, "RB"))
                .isCloseTo(expected, within(1e-9));
    }

    @Test
    void aQuarterbacksPassingLineEarnsItsBonuses() {
        Map<String, Double> line = Map.of("pass_yd", 312.0, "pass_td", 2.0, "pass_int", 1.0,
                "pass_cmp", 26.0, "rush_yd", 20.0, "bonus_pass_yd_300", 1.0,
                "bonus_pass_cmp_25", 1.0);

        assertThat(priced(FOUR_POINT_PASSING, line, "QB"))
                .isCloseTo(312 * 0.04 + 2 * 4 - 2 + 20 * 0.1 + 2 + 1, within(1e-9));
    }

    @Test
    void aKickersAndADefensesLinesPriceByTheirOwnKeys() {
        LeagueScoring scoring = new LeagueScoring(Map.of("fgm_40_49", 4.0, "xpm", 1.0,
                "fgmiss", -1.0, "sack", 1.0, "int", 2.0, "pts_allow_7_13", 4.0));

        assertThat(priced(scoring, Map.of("fgm_40_49", 1.0, "xpm", 3.0, "fgmiss", 1.0), "K"))
                .isCloseTo(4 + 3 - 1, within(1e-9));
        assertThat(priced(scoring, Map.of("sack", 3.0, "int", 1.0, "pts_allow_7_13", 1.0),
                "DEF")).isCloseTo(3 + 2 + 4, within(1e-9));
    }

    @Test
    void aLineWithNoPositionEarnsNoPremium() {
        Map<String, Double> line = Map.of("rec", 5.0, "rec_yd", 50.0);

        assertThat(priced(HALF_PPR_TE_PREMIUM, line, null))
                .isCloseTo(5 * 0.5 + 50 * 0.1, within(1e-9));
    }

    @Test
    void aLineWithNothingTheLeagueScoresHasNoPrice() {
        assertThat(HALF_PPR_TE_PREMIUM.price(Map.of("gp", 1.0, "off_snp", 12.0), "WR"))
                .isEmpty();
    }
}
