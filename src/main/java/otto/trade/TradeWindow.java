package otto.trade;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;
import java.util.function.Supplier;

import otto.check.SeasonWeek;
import otto.nflverse.Schedule;
import otto.sleeper.SleeperAdapter;

/**
 * Where trading in the league stands: open with nothing limiting it, the
 * deadline week itself, any time after trading closed, or a league whose
 * trades are turned off.
 *
 * <p>Sleeper closes trading when the last game of the deadline week ends,
 * usually Monday night, while its own week number stays on the deadline
 * week for a day or two more. So inside the deadline week the week number
 * can't say whether trading is open; the games can.
 */
public sealed interface TradeWindow {

    /**
     * Returns what this means for one trade the user is weighing, in a
     * sentence or two; empty when nothing limits trading.
     */
    Optional<String> note();

    /**
     * Returns why no trade can be made, as a clause that ends by pointing
     * to waivers; empty while trades are open.
     */
    Optional<String> closedBecause();

    /** Returns true when a trade can no longer be proposed and accepted. */
    default boolean closed() {
        return closedBecause().isPresent();
    }

    /**
     * Reads the league's trade rules against the week and the clock.
     *
     * <p>In the deadline week trading is closed once that week's last game
     * has ended by the schedule. When the schedule cannot say, it is closed
     * from 08:00 UTC on the Tuesday after the week's first game, a few
     * hours after a Monday night game ends, and stays open when Sleeper
     * gives no season start to count that Tuesday from.
     *
     * @param week the current week, empty when it is unknown
     * @param schedule reads the stored schedule; asked only in the
     *        deadline week, and empty when none is stored
     * @return {@link Open} when trades are on and the league has no
     *         deadline, the week is unknown, or the deadline week is still
     *         ahead
     */
    static TradeWindow assess(SleeperAdapter.League league,
            Optional<SeasonWeek> week, Supplier<Optional<Schedule>> schedule, Instant now) {
        if (league.tradesDisabled()) {
            return new TradesOff();
        }
        if (league.tradeDeadline().isEmpty() || week.isEmpty()) {
            return new Open();
        }
        int deadline = league.tradeDeadline().get();
        SeasonWeek current = week.get();
        if (current.number() < deadline) {
            return new Open();
        }
        boolean closed = current.number() > deadline
                || lastGameEnded(current, schedule.get(), now);
        return closed ? new Passed(deadline) : new FinalWeek(deadline);
    }

    private static boolean lastGameEnded(SeasonWeek week, Optional<Schedule> schedule,
            Instant now) {
        return schedule.flatMap(stored -> stored.weekOver(week.season(), week.number(), now))
                .or(() -> week.firstGameDay()
                        .map(day -> day.with(TemporalAdjusters.next(DayOfWeek.TUESDAY))
                                .atTime(LocalTime.of(8, 0)).toInstant(ZoneOffset.UTC))
                        .map(close -> !now.isBefore(close)))
                .orElse(false);
    }

    /** Nothing limits trading this week. */
    record Open() implements TradeWindow {

        @Override
        public Optional<String> note() {
            return Optional.empty();
        }

        @Override
        public Optional<String> closedBecause() {
            return Optional.empty();
        }
    }

    /** This is the deadline week and its last game has not ended, so trades are open. */
    record FinalWeek(int week) implements TradeWindow {

        @Override
        public Optional<String> note() {
            return Optional.of(("The trade deadline is this week. Trades close when week "
                    + "%d's last game ends, usually Monday night. A trade has to be proposed "
                    + "and accepted before then, and its review period can finish afterward.")
                    .formatted(week));
        }

        @Override
        public Optional<String> closedBecause() {
            return Optional.empty();
        }
    }

    /** The deadline week's last game has ended, so no trade can be made. */
    record Passed(int week) implements TradeWindow {

        @Override
        public Optional<String> note() {
            return Optional.of(("The trade deadline passed after week %d. This trade can no "
                    + "longer be submitted, but here is what it would have been worth.")
                    .formatted(week));
        }

        @Override
        public Optional<String> closedBecause() {
            return Optional.of(("the trade deadline passed after week %d, so trades are "
                    + "closed and only waiver moves are left").formatted(week));
        }
    }

    /** The commissioner has turned trades off for the whole season. */
    record TradesOff() implements TradeWindow {

        @Override
        public Optional<String> note() {
            return Optional.of("Trades are turned off in this league for the whole season. "
                    + "This trade can't be submitted, but here is what it would be worth.");
        }

        @Override
        public Optional<String> closedBecause() {
            return Optional.of("trades are turned off in this league for the whole season, "
                    + "so only waiver moves are left");
        }
    }
}
