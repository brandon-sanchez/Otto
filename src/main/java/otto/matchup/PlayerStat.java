package otto.matchup;

import java.util.List;

import otto.nflverse.FeedId;

/**
 * The player stats Otto ranks within a position, season to date. Each
 * minimum is a season total, so a player needs a few games of work
 * before he is ranked.
 */
public enum PlayerStat implements RankedStat {

    /** Targets per game he played, from the weekly stats. */
    TARGETS_PER_GAME("targets per game", Direction.HIGHER_IS_BETTER, "targets", 10, false,
            List.of(FeedId.WEEKLY_STATS)),

    /** His targets over his team's in the games he played, from 0 to 1. */
    TARGET_SHARE("target share", Direction.HIGHER_IS_BETTER, "targets", 10, true,
            List.of(FeedId.WEEKLY_STATS)),

    /** Carries per game he played, from the weekly stats. */
    CARRIES_PER_GAME("carries per game", Direction.HIGHER_IS_BETTER, "carries", 20, false,
            List.of(FeedId.WEEKLY_STATS)),

    /** His carries over his team's in the games he played, from 0 to 1. */
    CARRY_SHARE("carry share", Direction.HIGHER_IS_BETTER, "carries", 20, true,
            List.of(FeedId.WEEKLY_STATS)),

    /** The mean of his per-game share of his team's offensive snaps, from 0 to 1. */
    SNAP_SHARE("snap share", Direction.HIGHER_IS_BETTER, "games", 2, true,
            List.of(FeedId.SNAP_COUNTS)),

    /** PFR's yards before contact per carry, from the rushing advanced stats. */
    YARDS_BEFORE_CONTACT_PER_CARRY("yards before contact per carry",
            Direction.HIGHER_IS_BETTER, "carries", 20, false,
            List.of(FeedId.RUSHING_ADVANCED_STATS)),

    /** How often a passer was pressured per dropback, from {@code PressureRates}. */
    PRESSURED_RATE("pressured rate", Direction.HIGHER_IS_WORSE, "dropbacks", 50, true,
            List.of(FeedId.PFR_PASSING, FeedId.PLAY_BY_PLAY));

    private final String label;
    private final Direction direction;
    private final String sampleUnit;
    private final int minimumSample;
    private final boolean percentage;
    private final List<FeedId> feeds;

    PlayerStat(String label, Direction direction, String sampleUnit, int minimumSample,
            boolean percentage, List<FeedId> feeds) {
        this.label = label;
        this.direction = direction;
        this.sampleUnit = sampleUnit;
        this.minimumSample = minimumSample;
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
        return minimumSample;
    }

    @Override
    public boolean highestRanksFirst() {
        return true;
    }

    @Override
    public boolean percentage() {
        return percentage;
    }

    @Override
    public List<FeedId> feeds() {
        return feeds;
    }
}
