package otto.trade;

import java.util.Optional;
import java.util.OptionalInt;

sealed interface TradeDeadline {

    String message();

    boolean passed();

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
