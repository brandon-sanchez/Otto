package otto.board;

import java.util.Optional;

import otto.settings.Settings;
import otto.sleeper.SleeperAdapter;
import otto.snapshot.RosterSnapshot;

/**
 * The bounds code sets on the board's FAAB bids. The model chooses each
 * range and target; this decides whether a dollar amount may be stated
 * at all and, when it may, the lowest and highest legal bid.
 */
sealed interface BidPolicy {

    /**
     * Sleeper takes bids in whole dollars, so every bid moves in steps
     * of one dollar. No league setting changes it.
     */
    int INCREMENT = 1;

    /** The league claims by waiver priority, so a claim carries no bid. */
    record NoBids() implements BidPolicy {
    }

    /**
     * The league bids FAAB, but its budget or its minimum bid could not
     * be verified, so no dollar amount may be stated. This is never the
     * same as a $0 budget, which is a verified budget with nothing left.
     *
     * @param reason what could not be verified, in plain words
     */
    record Withheld(String reason) implements BidPolicy {
    }

    /**
     * Bids the league and the user allow.
     *
     * @param remaining the user's budget left, in dollars, possibly 0
     * @param minimum the league's smallest legal bid, in dollars
     * @param limit the most the user will bid on one player, empty when
     *        he set no limit
     */
    record Verified(int remaining, int minimum, Optional<Integer> limit) implements BidPolicy {

        /** Returns the highest legal bid on one player. */
        int ceiling() {
            return Math.min(remaining, limit.orElse(remaining));
        }

        /** Returns false when even the minimum bid is above the ceiling. */
        boolean anyLegalBid() {
            return ceiling() >= minimum;
        }
    }

    /**
     * Returns the bounds for the user's next claim round. FAAB is read
     * from the league's waiver type, never from a budget being present,
     * and the budget left is the league budget less what the user's
     * roster document says he spent.
     */
    static BidPolicy of(SleeperAdapter.League league, RosterSnapshot user, Settings settings) {
        Optional<Boolean> faab = league.rules().faab();
        if (faab.isEmpty()) {
            return new Withheld("Sleeper didn't say whether this league uses FAAB bidding");
        }
        if (!faab.get()) {
            return new NoBids();
        }
        if (league.waiverBudget().isEmpty()) {
            return new Withheld("I couldn't read the league's FAAB budget");
        }
        if (league.rules().minimumBid().isEmpty()) {
            return new Withheld("I couldn't read the league's minimum bid");
        }
        int remaining = league.waiverBudget().get() - user.waiverBudgetUsed();
        if (remaining < 0) {
            return new Withheld("Sleeper shows more FAAB spent than the league's budget");
        }
        return new Verified(remaining, league.rules().minimumBid().get(), settings.bidLimit());
    }

    /** Returns the verified budget left, empty unless bids are verified. */
    default Optional<Integer> budget() {
        return this instanceof Verified verified ? Optional.of(verified.remaining())
                : Optional.empty();
    }
}
