package otto.matchup;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RankingTest {

    private static final Ranking.Weeks WEEKS_1_TO_6 = new Ranking.Weeks("2026", false, 1, 6);

    private static Ranking.Measurement tightEnd(String id, Double targetsPerGame, int targets) {
        return new Ranking.Measurement(id, "TE " + id, targetsPerGame, targets);
    }

    @Test
    void rankOneIsTheHighestValueAndEveryRankCountsFromBothEnds() {
        Ranking ranking = Ranking.rank(PlayerStat.TARGETS_PER_GAME, Position.TE, WEEKS_1_TO_6, List.of(
                tightEnd("a", 5.5, 33),
                tightEnd("b", 8.0, 48),
                tightEnd("c", 3.0, 18)));

        assertThat(ranking.ranked()).extracting(Ranking.Placement::id)
                .containsExactly("b", "a", "c");
        Ranking.Placement top = ranking.placement("b").orElseThrow();
        assertThat(top.rank()).isEqualTo(1);
        assertThat(top.rankFromBottom()).isEqualTo(3);
        assertThat(top.value()).isEqualTo(8.0);
        assertThat(top.sample()).isEqualTo(48);
        Ranking.Placement last = ranking.placement("c").orElseThrow();
        assertThat(last.rank()).isEqualTo(3);
        assertThat(last.rankFromBottom()).isEqualTo(1);
    }

    @Test
    void equalValuesShareARank() {
        Ranking ranking = Ranking.rank(PlayerStat.TARGETS_PER_GAME, Position.TE, WEEKS_1_TO_6, List.of(
                tightEnd("a", 6.0, 36),
                tightEnd("b", 6.0, 30),
                tightEnd("c", 7.0, 42),
                tightEnd("d", 2.0, 12)));

        assertThat(ranking.placement("a").orElseThrow().rank()).isEqualTo(2);
        assertThat(ranking.placement("b").orElseThrow().rank()).isEqualTo(2);
        assertThat(ranking.placement("d").orElseThrow().rank()).isEqualTo(4);
        assertThat(ranking.placement("a").orElseThrow().rankFromBottom()).isEqualTo(2);
        assertThat(ranking.placement("c").orElseThrow().rankFromBottom()).isEqualTo(4);
    }

    @Test
    void theHardestRemainingScheduleIsFirstAndRedAndEqualSchedulesShareARank() {
        Ranking ranking = Ranking.rank(ScheduleStat.REMAINING_SCHEDULE, Position.RB,
                new Ranking.Weeks("2026", false, 10, 18), List.of(
                        new Ranking.Measurement("NYG", "NYG", 22.0, 8),
                        new Ranking.Measurement("LV", "LV", 14.0, 8),
                        new Ranking.Measurement("GB", "GB", 14.0, 7),
                        new Ranking.Measurement("MIN", "MIN", 18.0, 8)));

        assertThat(ranking.population().describe()).isEqualTo("of 4 teams");
        Ranking.Placement raiders = ranking.placement("LV").orElseThrow();
        Ranking.Placement packers = ranking.placement("GB").orElseThrow();
        assertThat(raiders.rank()).isEqualTo(1);
        assertThat(packers.rank()).isEqualTo(1);
        assertThat(raiders.rankFromBottom()).isEqualTo(3);
        assertThat(ranking.tone(raiders)).isEqualTo(Tone.RED);
        Ranking.Placement giants = ranking.placement("NYG").orElseThrow();
        assertThat(giants.rank()).isEqualTo(4);
        assertThat(giants.rankFromBottom()).isEqualTo(1);
        assertThat(ranking.tone(giants)).isEqualTo(Tone.GREEN);
    }

    @Test
    void aPlayerIsRankedHoweverSmallHisSample() {
        Ranking ranking = Ranking.rank(PlayerStat.TARGETS_PER_GAME, Position.TE, WEEKS_1_TO_6, List.of(
                tightEnd("a", 5.0, 30),
                tightEnd("rookie", 9.0, 1)));

        assertThat(ranking.placement("rookie").orElseThrow().rank()).isEqualTo(1);
        assertThat(ranking.population().size()).isEqualTo(2);
    }

    @Test
    void aDefenseWithNoGameIsNotRankedOrCountedInThePopulation() {
        Ranking ranking = Ranking.rank(DefenseStat.POINTS_ALLOWED, Position.RB, WEEKS_1_TO_6,
                List.of(new Ranking.Measurement("DET", "DET", 20.0, 6),
                        new Ranking.Measurement("NEW", "NEW", 0.0, 0)));

        assertThat(ranking.placement("NEW")).isEmpty();
        assertThat(ranking.unranked()).containsExactly(new Ranking.Unranked(
                "NEW", "NEW", Ranking.Reason.BELOW_MINIMUM, 0));
        assertThat(ranking.population().size()).isEqualTo(1);
    }

    @Test
    void aMissingValueIsUnrankedRatherThanRankedAsZero() {
        Ranking ranking = Ranking.rank(PlayerStat.TARGETS_PER_GAME, Position.TE, WEEKS_1_TO_6, List.of(
                tightEnd("a", 5.0, 30),
                tightEnd("b", 0.0, 10),
                tightEnd("unknown", null, 0)));

        assertThat(ranking.placement("unknown")).isEmpty();
        assertThat(ranking.unranked()).extracting(Ranking.Unranked::reason)
                .containsExactly(Ranking.Reason.MISSING);
        assertThat(ranking.placement("b").orElseThrow().rankFromBottom()).isEqualTo(1);
        assertThat(ranking.population().size()).isEqualTo(2);
    }

    @Test
    void theRankRecordsItsPopulationWeeksAndDirection() {
        List<Ranking.Measurement> tightEnds = List.of(
                tightEnd("a", 5.0, 30), tightEnd("b", 4.0, 24), tightEnd("c", 1.0, 3));

        Ranking ranking = Ranking.rank(PlayerStat.TARGETS_PER_GAME, Position.TE, WEEKS_1_TO_6, tightEnds);

        assertThat(ranking.population().describe())
                .isEqualTo("of 3 tight ends");
        assertThat(ranking.weeks()).isEqualTo(WEEKS_1_TO_6);
        assertThat(ranking.stat().direction()).isEqualTo(Direction.HIGHER_IS_BETTER);
        assertThat(PlayerStat.PRESSURED_RATE.direction()).isEqualTo(Direction.HIGHER_IS_WORSE);
    }

    @Test
    void aDefenseThatAllowsTheLeastRanksFirstAsTheToughestMatchup() {
        Ranking ranking = Ranking.rank(DefenseStat.POINTS_ALLOWED, Position.RB, WEEKS_1_TO_6, List.of(
                new Ranking.Measurement("DET", "DET", 32.5, 6),
                new Ranking.Measurement("MIA", "MIA", 13.0, 6),
                new Ranking.Measurement("CHI", "CHI", 18.0, 6)));

        assertThat(ranking.ranked()).extracting(Ranking.Placement::id)
                .containsExactly("MIA", "CHI", "DET");
        assertThat(ranking.tone(ranking.placement("MIA").orElseThrow())).isEqualTo(Tone.RED);
        assertThat(ranking.tone(ranking.placement("CHI").orElseThrow())).isEqualTo(Tone.NEUTRAL);
        assertThat(ranking.tone(ranking.placement("DET").orElseThrow())).isEqualTo(Tone.GREEN);
    }

    @Test
    void aDefenseThatPressuresTheMostRanksFirstAsTheToughestMatchup() {
        Ranking ranking = Ranking.rank(DefenseStat.PRESSURE_RATE, Position.QB, WEEKS_1_TO_6, List.of(
                new Ranking.Measurement("NO", "NO", 0.29, 6),
                new Ranking.Measurement("MIN", "MIN", 0.41, 6)));

        assertThat(ranking.placement("MIN").orElseThrow().rank()).isEqualTo(1);
        assertThat(ranking.tone(ranking.placement("MIN").orElseThrow())).isEqualTo(Tone.RED);
        assertThat(ranking.tone(ranking.placement("NO").orElseThrow())).isEqualTo(Tone.GREEN);
    }

    @Test
    void aPlayerStatRanksTheMostFirstAndItsToneFollowsItsDirection() {
        Ranking targets = Ranking.rank(PlayerStat.TARGETS_PER_GAME, Position.TE, WEEKS_1_TO_6, List.of(
                tightEnd("a", 8.0, 48), tightEnd("b", 3.0, 18)));
        Ranking pressured = Ranking.rank(PlayerStat.PRESSURED_RATE, Position.QB, WEEKS_1_TO_6, List.of(
                new Ranking.Measurement("q1", "Q1", 0.40, 200),
                new Ranking.Measurement("q2", "Q2", 0.20, 200)));

        assertThat(targets.tone(targets.placement("a").orElseThrow())).isEqualTo(Tone.GREEN);
        assertThat(pressured.placement("q1").orElseThrow().rank()).isEqualTo(1);
        assertThat(pressured.tone(pressured.placement("q1").orElseThrow())).isEqualTo(Tone.RED);
    }

    @Test
    void aDefensePopulationNeedsNoSampleClauseWhenOneGameIsEnough() {
        Ranking ranking = Ranking.rank(DefenseStat.PRESSURE_RATE, Position.QB,
                new Ranking.Weeks("2025", true, 1, 18), List.of(
                        new Ranking.Measurement("MIN", "MIN", 0.41, 17),
                        new Ranking.Measurement("NO", "NO", 0.29, 17)));

        assertThat(ranking.population().describe()).isEqualTo("of 2 defenses");
        assertThat(ranking.weeks().priorSeasonFinal()).isTrue();
        assertThat(ranking.placement("NO").orElseThrow().rank()).isEqualTo(2);
    }

    @Test
    void aRankLineLeadsWithTheBoldRankColouredByItsQuarter() {
        Ranking ranking = Ranking.rank(PlayerStat.TARGETS_PER_GAME, Position.TE, WEEKS_1_TO_6, List.of(
                tightEnd("a", 5.5, 33),
                tightEnd("b", 8.0, 48),
                tightEnd("c", 3.0, 18),
                tightEnd("d", 1.0, 6)));

        assertThat(RankWords.line(ranking, ranking.placement("b").orElseThrow()))
                .isEqualTo("🟢 Targets per game: <b>1st</b> of 4 (8.0)");
        assertThat(RankWords.line(ranking, ranking.placement("d").orElseThrow()))
                .isEqualTo("🔴 Targets per game: <b>4th</b> of 4 (1.0)");
        assertThat(RankWords.header("Travis Kelce", Position.TE, WEEKS_1_TO_6))
                .isEqualTo("Travis Kelce's rankings among TEs this season (weeks 1–6)");
    }
}
