package otto.trade;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * Where the league's trade deadline stands this week, when it is close
 * enough to matter: the deadline week itself, or any week after it.
 */
public sealed interface TradeDeadline {

    /** Returns what the deadline means for a trade this week, in a sentence. */
    String message();

    /** Returns true once trades can no longer be submitted. */
    boolean passed();

    /**
     * Reads the deadline against the current week.
     *
     * @return empty when the league has no deadline, the week is unknown,
     *         or the deadline week is still ahead
     */
    static Optional<TradeDeadline> assess(Optional<Integer> configuredWeek,
            OptionalInt currentWeek) {
        if (configuredWeek.isEmpty() || currentWeek.isEmpty()) {
            return Optional.empty();
        }
        int deadline = configuredWeek.get();
        int current = currentWeek.getAsInt();
        if (current > deadline) {
            return Optional.of(new Passed(deadline));
        }
        if (current == deadline) {
            return Optional.of(new FinalWeek(deadline));
        }
        return Optional.empty();
    }

    /** This is the deadline week: trades are still legal until it ends. */
    record FinalWeek(int week) implements TradeDeadline {

        @Override
        public String message() {
            return "The trade deadline is this week, week %d. This trade is still legal, "
                    .formatted(week)
                    + "but it is nearly out of time.";
        }

        @Override
        public boolean passed() {
            return false;
        }
    }

    /** The deadline week is over, so no trade can be submitted. */
    record Passed(int week) implements TradeDeadline {

        @Override
        public String message() {
            return "The trade deadline passed after week %d. This trade can no longer be "
                    .formatted(week)
                    + "submitted, but here is what it would have been worth.";
        }

        @Override
        public boolean passed() {
            return true;
        }
    }
}
