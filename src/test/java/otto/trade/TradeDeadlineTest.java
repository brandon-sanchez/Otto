package otto.trade;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import otto.check.SeasonWeek;
import otto.nflverse.Schedule;
import otto.sleeper.SleeperAdapter;

import static org.assertj.core.api.Assertions.assertThat;

class TradeDeadlineTest {

    private static final Instant THURSDAY_KICKOFF = Instant.parse("2026-12-04T01:15:00Z");
    private static final Instant MONDAY_KICKOFF = Instant.parse("2026-12-08T01:15:00Z");
    private static final Instant MONDAY_AFTERNOON = Instant.parse("2026-12-07T19:00:00Z");

    private static final Optional<LocalDate> SEASON_START = Optional.of(LocalDate.of(2026, 9, 10));

    private static SleeperAdapter.League league(Optional<Integer> deadline, boolean disabled) {
        return new SleeperAdapter.League("1", "Raising Canes Demon", "in_season", List.of(),
                Map.of(), 6, 15, Optional.empty(), deadline, disabled, null);
    }

    private static SleeperAdapter.League deadlineInWeek13() {
        return league(Optional.of(13), false);
    }

    private static Optional<SeasonWeek> week(int number) {
        return Optional.of(new SeasonWeek("2026", number, "regular", SEASON_START));
    }

    private static Optional<Schedule> week13Schedule(boolean finished) {
        return schedule(
                game("2026_13_DAL_DET", 13, THURSDAY_KICKOFF, finished),
                game("2026_13_NYG_WAS", 13, MONDAY_KICKOFF, finished));
    }

    private static Optional<Schedule> schedule(Schedule.Game... games) {
        return Optional.of(new Schedule("2026", THURSDAY_KICKOFF, THURSDAY_KICKOFF, null,
                List.of(games)));
    }

    private static Schedule.Game game(String id, int week, Instant kickoff, boolean finished) {
        return new Schedule.Game(id, "2026", week, kickoff, "WAS", "NYG", finished, null, null,
                null, null);
    }

    private static Optional<TradeDeadline> assess(SleeperAdapter.League league,
            Optional<SeasonWeek> week, Optional<Schedule> schedule, String now) {
        return TradeDeadline.assess(league, week, () -> schedule, Instant.parse(now));
    }

    @Test
    void nothingLimitsTradingBeforeTheDeadlineWeek() {
        assertThat(assess(deadlineInWeek13(), week(12), week13Schedule(false),
                "2026-12-01T12:00:00Z")).isEmpty();
    }

    @Test
    void tradesAreOpenOnMondayBeforeTheLastGameOfTheDeadlineWeek() {
        Optional<TradeDeadline> deadline = TradeDeadline.assess(deadlineInWeek13(), week(13),
                () -> week13Schedule(false), MONDAY_AFTERNOON);

        assertThat(deadline).contains(new TradeDeadline.FinalWeek(13));
        assertThat(deadline.get().closed()).isFalse();
    }

    @Test
    void tradesAreOpenWhileTheLastGameIsBeingPlayed() {
        assertThat(assess(deadlineInWeek13(), week(13), week13Schedule(false),
                "2026-12-08T04:44:00Z")).contains(new TradeDeadline.FinalWeek(13));
    }

    @Test
    void tradesCloseAGamesLengthAfterTheLastKickoffOfTheDeadlineWeek() {
        Optional<TradeDeadline> deadline = assess(deadlineInWeek13(), week(13),
                week13Schedule(false), "2026-12-08T04:45:00Z");

        assertThat(deadline).contains(new TradeDeadline.Passed(13));
        assertThat(deadline.get().closed()).isTrue();
    }

    @Test
    void tradesCloseOnceEveryGameOfTheDeadlineWeekHasAResult() {
        assertThat(assess(deadlineInWeek13(), week(13), week13Schedule(true),
                "2026-12-08T04:00:00Z")).contains(new TradeDeadline.Passed(13));
    }

    @Test
    void tradesStayClosedOnTuesdayMorningWhileSleeperStillSaysTheDeadlineWeek() {
        assertThat(assess(deadlineInWeek13(), week(13), week13Schedule(false),
                "2026-12-08T14:00:00Z")).contains(new TradeDeadline.Passed(13));
    }

    @Test
    void tradesAreClosedInEveryWeekAfterTheDeadlineWeek() {
        assertThat(assess(deadlineInWeek13(), week(14), Optional.empty(),
                "2026-12-10T12:00:00Z")).contains(new TradeDeadline.Passed(13));
    }

    @Test
    void withoutAScheduleTradesAreOpenUntilEightUtcOnTuesday() {
        assertThat(assess(deadlineInWeek13(), week(13), Optional.empty(),
                "2026-12-08T07:59:59Z")).contains(new TradeDeadline.FinalWeek(13));
    }

    @Test
    void withoutAScheduleTradesCloseAtEightUtcOnTuesday() {
        assertThat(assess(deadlineInWeek13(), week(13), Optional.empty(),
                "2026-12-08T08:00:00Z")).contains(new TradeDeadline.Passed(13));
    }

    @Test
    void aScheduleThatDoesNotListTheDeadlineWeekFallsBackToTuesday() {
        Optional<Schedule> otherWeeks = schedule(
                game("2026_12_NYG_WAS", 12, Instant.parse("2026-11-29T18:00:00Z"), true));

        assertThat(assess(deadlineInWeek13(), week(13), otherWeeks, "2026-12-08T07:59:59Z"))
                .contains(new TradeDeadline.FinalWeek(13));
        assertThat(assess(deadlineInWeek13(), week(13), otherWeeks, "2026-12-08T08:00:00Z"))
                .contains(new TradeDeadline.Passed(13));
    }

    @Test
    void withNoScheduleAndNoSeasonStartTheDeadlineWeekReadsAsOpen() {
        Optional<SeasonWeek> undated = Optional.of(
                new SeasonWeek("2026", 13, "regular", Optional.empty()));

        assertThat(assess(deadlineInWeek13(), undated, Optional.empty(),
                "2026-12-08T14:00:00Z")).contains(new TradeDeadline.FinalWeek(13));
    }

    @Test
    void aLastGameWithNoKickoffTimeListedFallsBackToTuesday() {
        Optional<Schedule> untimed = schedule(
                game("2026_13_DAL_DET", 13, THURSDAY_KICKOFF, true),
                game("2026_13_NYG_WAS", 13, Instant.parse("2026-12-07T05:00:00Z"), false));

        assertThat(assess(deadlineInWeek13(), week(13), untimed, "2026-12-07T19:00:00Z"))
                .contains(new TradeDeadline.FinalWeek(13));
        assertThat(assess(deadlineInWeek13(), week(13), untimed, "2026-12-08T08:00:00Z"))
                .contains(new TradeDeadline.Passed(13));
    }

    @Test
    void aLeagueWithNoDeadlineNeverCloses() {
        assertThat(assess(league(Optional.empty(), false), week(17), Optional.empty(),
                "2027-01-05T12:00:00Z")).isEmpty();
    }

    @Test
    void anUnknownWeekSaysNothingAboutTheDeadline() {
        assertThat(assess(deadlineInWeek13(), Optional.empty(), Optional.empty(),
                "2026-12-08T14:00:00Z")).isEmpty();
    }

    @Test
    void tradesTurnedOffAreClosedInEveryWeekAndWithoutAWeek() {
        SleeperAdapter.League off = league(Optional.of(13), true);

        assertThat(assess(off, week(2), Optional.empty(), "2026-09-20T12:00:00Z"))
                .contains(new TradeDeadline.TradesOff());
        assertThat(assess(off, Optional.empty(), Optional.empty(), "2026-09-20T12:00:00Z"))
                .contains(new TradeDeadline.TradesOff());
    }
}
