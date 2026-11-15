package otto.matchup;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import otto.nflverse.FeedId;

/**
 * The defense stats Otto ranks against a position, season to date.
 * Every one is ranked from a sample of one up, and its direction is read
 * from the opposing offense.
 */
public enum DefenseStat implements RankedStat {

    /**
     * Fantasy points allowed per game to the position, priced in the
     * league's own scoring by the defense-versus-position table.
     */
    POINTS_ALLOWED("fantasy points allowed per game", Direction.HIGHER_IS_BETTER,
            EnumSet.allOf(Position.class), "games", false,
            List.of(FeedId.WEEKLY_STATS)),

    /** Rushing yards allowed per game, from the defense-versus-position table. */
    RUSHING_YARDS_ALLOWED("rushing yards allowed per game", Direction.HIGHER_IS_BETTER,
            EnumSet.of(Position.RB), "games", false,
            List.of(FeedId.WEEKLY_STATS)),

    /** Yards before contact allowed per carry, over every opposing ball carrier. */
    YARDS_BEFORE_CONTACT_ALLOWED("yards before contact allowed per carry",
            Direction.HIGHER_IS_BETTER, EnumSet.of(Position.RB), "games", false,
            List.of(FeedId.RUSHING_ADVANCED_STATS)),

    /** Pressures per opposing dropback, from {@code PressureRates}. */
    PRESSURE_RATE("pressure rate", Direction.HIGHER_IS_WORSE, EnumSet.of(Position.QB), "games",
            true, List.of(FeedId.PFR_DEFENSE, FeedId.PLAY_BY_PLAY)),

    /**
     * Blitzed dropbacks per opposing dropback, from {@code BlitzRates}.
     * Its sample is the dropbacks the defense faced.
     */
    BLITZ_RATE("blitz rate", Direction.HIGHER_IS_WORSE, EnumSet.of(Position.QB), "dropbacks", true,
            List.of(FeedId.FTN_CHARTING, FeedId.PLAY_BY_PLAY));

    private final String label;
    private final Direction direction;
    private final Set<Position> positions;
    private final String sampleUnit;
    private final boolean percentage;
    private final List<FeedId> feeds;

    DefenseStat(String label, Direction direction, Set<Position> positions, String sampleUnit,
            boolean percentage, List<FeedId> feeds) {
        this.label = label;
        this.direction = direction;
        this.positions = Collections.unmodifiableSet(positions);
        this.sampleUnit = sampleUnit;
        this.percentage = percentage;
        this.feeds = feeds;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public Direction direction() {
        return direction;
    }

    @Override
    public String sampleUnit() {
        return sampleUnit;
    }

    @Override
    public int minimumSample() {
        return 1;
    }

    @Override
    public boolean highestRanksFirst() {
        return direction == Direction.HIGHER_IS_WORSE;
    }

    @Override
    public boolean percentage() {
        return percentage;
    }

    @Override
    public List<FeedId> feeds() {
        return feeds;
    }

    /** Returns the positions a rank of this stat is said to be against. */
    public Set<Position> positions() {
        return positions;
    }
}
