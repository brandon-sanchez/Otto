package otto.buysell;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import otto.nflverse.Schedule;

import static org.assertj.core.api.Assertions.assertThat;

class AssessmentTest {

    private static final Instant KICKOFF = Instant.parse("2026-09-13T17:00:00Z");

    private static PlayerSeason.Game game(int week, double points, double expected) {
        double touchdowns = 0;
        double expectedTouchdowns = 0;
        double targets = 7;
        double teamTargets = 35;
        double carries = 0;
        double teamCarries = 25;
        return new PlayerSeason.Game(week, "DAL", points, expected, touchdowns,
                expectedTouchdowns, targets, teamTargets, carries, teamCarries);
    }

    private static PlayerSeason receiver(int games, double points, double expected) {
        return new PlayerSeason("00-0000001", "WR",
                IntStream.rangeClosed(1, games).mapToObj(week -> game(week, points, expected))
                        .toList());
    }

    private static Assessment.Subject subject(PlayerSeason thisSeason, PlayerSeason lastSeason,
            Optional<Integer> finish, List<Double> snaps, Optional<MarketMove> market) {
        return new Assessment.Subject(thisSeason, Optional.of(lastSeason), finish,
                Optional.of(9.0), Optional.empty(), Optional.empty(), snaps, TeamContext.UNKNOWN,
                market);
    }

    @Test
    void twoGamesAreTooFewForAnyCall() {
        Assessment assessment = Assessment.of(subject(receiver(2, 35, 15), receiver(17, 15, 15),
                Optional.of(10), List.of(), Optional.empty()));

        assertThat(assessment.call()).isEqualTo(Assessment.Call.NO_CALL);
    }

    @Test
    void aProvenPlayerWhoseUsageHeldWhileHeScoredBelowItIsABuyLow() {
        Assessment assessment = Assessment.of(subject(receiver(4, 7, 15), receiver(17, 16, 15),
                Optional.of(10), List.of(), Optional.empty()));

        assertThat(assessment.call()).isEqualTo(Assessment.Call.BUY_LOW);
    }

    @Test
    void aSnapShareDropOfMoreThanFifteenPointsRulesOutABuyLow() {
        Assessment assessment = Assessment.of(subject(receiver(4, 7, 15), receiver(17, 16, 15),
                Optional.of(10), List.of(0.85, 0.84, 0.66, 0.64), Optional.empty()));

        assertThat(assessment.call()).isEqualTo(Assessment.Call.NO_CALL);
        assertThat(assessment.exclusions())
                .containsExactly("his snap share fell more than 15 points over his last two games");
    }

    @Test
    void aFallingValueWithFallingUsageNeverMakesABuyLow() {
        Assessment assessment = Assessment.of(subject(receiver(4, 8, 9), receiver(17, 14, 15),
                Optional.of(60), List.of(), Optional.of(new MarketMove(-30, 30, "FantasyCalc"))));

        assertThat(assessment.call()).isEqualTo(Assessment.Call.NO_CALL);
        assertThat(assessment.marketRead()).isEqualTo(Assessment.MarketRead.FELL_WITH_USAGE);
    }

    @Test
    void aFallingValueWhileUsageHeldSurfacesABuyLowOnPriceAlone() {
        Assessment assessment = Assessment.of(subject(receiver(4, 15, 15), receiver(17, 15, 15),
                Optional.of(60), List.of(), Optional.of(new MarketMove(-12, 30, "FantasyCalc"))));

        assertThat(assessment.call()).isEqualTo(Assessment.Call.MARKET_BUY_LOW);
    }

    @Test
    void aRisingValueWithoutMoreUsageIsTheMarketPayingForAStreak() {
        Assessment assessment = Assessment.of(subject(receiver(4, 20, 15), receiver(17, 15, 15),
                Optional.of(30), List.of(), Optional.of(new MarketMove(25, 30, "FantasyCalc"))));

        assertThat(assessment.call()).isEqualTo(Assessment.Call.SELL_HIGH);
        assertThat(assessment.marketRead()).isEqualTo(Assessment.MarketRead.PAYING_FOR_STREAK);
    }

    @Test
    void aTeamWhoseLatestGameWasStartedByAnotherQuarterbackHasMovedToABackup() {
        Schedule schedule = new Schedule("2026", KICKOFF, KICKOFF, null, List.of(
                game(1, "QB1"), game(2, "QB1"), game(3, "QB2")));

        TeamContext context = TeamContext.of(schedule, "2026", Optional.empty(),
                receiver(3, 10, 10), Optional.empty());

        assertThat(context.backupQuarterback()).isTrue();
    }

    private static Schedule.Game game(int week, String quarterback) {
        return new Schedule.Game("2026_0%d_DAL_NYG".formatted(week), "2026", week,
                KICKOFF.plusSeconds(604_800L * (week - 1)), "NYG", "DAL", true, "QBX",
                quarterback, "Coach A", "Coach B");
    }
}
