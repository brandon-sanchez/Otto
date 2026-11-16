package otto.matchup;

import java.util.List;

import otto.nflverse.FeedId;
import otto.nflverse.NflverseStore;

/**
 * A stat {@link LeagueRankings} can rank, with the rules every rank of
 * it follows.
 */
public sealed interface RankedStat permits PlayerStat, DefenseStat, ScheduleStat {

    /** Returns the stat's constant name, such as "TARGET_SHARE". */
    String name();

    /** Returns what the stat measures, in words fit to show the user, such as "targets per game". */
    String label();

    /** Returns whether a higher value helps or hurts the fantasy side. */
    Direction direction();

    /** Returns what the sample is counted in, plural, such as "targets" or "games". */
    String sampleUnit();

    /** Returns the smallest sample, in {@link #sampleUnit()}, that is ranked at all. */
    int minimumSample();

    /**
     * Returns true when rank 1 is the highest value. A player stat ranks
     * the most first. A defense stat ranks the toughest defense first: the
     * highest value when more is bad news for the offense facing it, such
     * as pressure rate, and the lowest when more is good news, such as
     * fantasy points allowed. A remaining schedule ranks the hardest
     * first, the one whose opponents allow the fewest points.
     */
    boolean highestRanksFirst();

    /** Returns true when a value is a share from 0 to 1, quoted as a percentage. */
    boolean percentage();

    /** Returns the stored feeds a value of it is built from. */
    List<FeedId> feeds();

    /** Returns {@link #feeds()} as one evidence source, such as "nflverse:ftn_charting+pbp". */
    default String source() {
        return NflverseStore.source(feeds());
    }
}
