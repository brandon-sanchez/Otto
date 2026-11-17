package otto.matchup;

import java.util.List;

import otto.nflverse.FeedId;

/**
 * The player stats Otto ranks, within a position or across every
 * position, over the season or a span of weeks. Every player with a
 * measured value is ranked, so a rank reads from week 1; a per-attempt
 * stat is always quoted with its sample, because one long carry can top
 * a list early in the season.
 */
public enum PlayerStat implements RankedStat {

    /** Targets per game he played, from the weekly stats. */
    TARGETS_PER_GAME("targets per game", Figure.AVERAGE, "targets", false,
            List.of(FeedId.WEEKLY_STATS)),

    /** His targets over his team's in the games he played, from 0 to 1. */
    TARGET_SHARE("target share", Figure.SHARE, "targets", false, List.of(FeedId.WEEKLY_STATS)),

    /** Carries per game he played, from the weekly stats. */
    CARRIES_PER_GAME("carries per game", Figure.AVERAGE, "carries", false,
            List.of(FeedId.WEEKLY_STATS)),

    /** His carries over his team's in the games he played, from 0 to 1. */
    CARRY_SHARE("carry share", Figure.SHARE, "carries", false, List.of(FeedId.WEEKLY_STATS)),

    /** The mean of his per-game share of his team's offensive snaps, from 0 to 1. */
    SNAP_SHARE("snap share", Figure.SHARE, "games", false, List.of(FeedId.SNAP_COUNTS)),

    /** PFR's yards before contact per carry, from the rushing advanced stats. */
    YARDS_BEFORE_CONTACT_PER_CARRY("yards before contact per carry", Figure.AVERAGE, "carries",
            true, List.of(FeedId.RUSHING_ADVANCED_STATS)),

    /**
     * How often a passer was pressured per dropback, from
     * {@code PressureRates}. It is measured over the season only, and a
     * higher value is worse for him.
     */
    PRESSURED_RATE("pressured rate", Figure.SHARE, "dropbacks", true,
            List.of(FeedId.PFR_PASSING, FeedId.PLAY_BY_PLAY)),

    /** His targets, from the weekly stats. */
    TARGETS("targets", Figure.TOTAL, "games", false, List.of(FeedId.WEEKLY_STATS)),

    /** His carries, from the weekly stats. */
    CARRIES("carries", Figure.TOTAL, "games", false, List.of(FeedId.WEEKLY_STATS)),

    /** His catches, from the weekly stats. */
    RECEPTIONS("receptions", Figure.TOTAL, "games", false, List.of(FeedId.WEEKLY_STATS)),

    /** His receiving yards, from the weekly stats. */
    RECEIVING_YARDS("receiving yards", Figure.TOTAL, "games", false,
            List.of(FeedId.WEEKLY_STATS)),

    /** His rushing yards, from the weekly stats. */
    RUSHING_YARDS("rushing yards", Figure.TOTAL, "games", false, List.of(FeedId.WEEKLY_STATS)),

    /** His passing yards, from the weekly stats. */
    PASSING_YARDS("passing yards", Figure.TOTAL, "games", false, List.of(FeedId.WEEKLY_STATS)),

    /** His receiving touchdowns, from the weekly stats. */
    RECEIVING_TOUCHDOWNS("receiving touchdowns", Figure.TOTAL, "games", false,
            List.of(FeedId.WEEKLY_STATS)),

    /** His rushing touchdowns, from the weekly stats. */
    RUSHING_TOUCHDOWNS("rushing touchdowns", Figure.TOTAL, "games", false,
            List.of(FeedId.WEEKLY_STATS)),

    /** His passing touchdowns, from the weekly stats. */
    PASSING_TOUCHDOWNS("passing touchdowns", Figure.TOTAL, "games", false,
            List.of(FeedId.WEEKLY_STATS)),

    /** Rushing yards over carries, from the weekly stats. */
    YARDS_PER_CARRY("yards per carry", Figure.AVERAGE, "carries", true,
            List.of(FeedId.WEEKLY_STATS)),

    /** Receiving yards over targets, from the weekly stats. */
    YARDS_PER_TARGET("yards per target", Figure.AVERAGE, "targets", true,
            List.of(FeedId.WEEKLY_STATS)),

    /** The air yards of his targets, summed from the play-by-play. */
    AIR_YARDS("air yards", Figure.TOTAL, "games", false, List.of(FeedId.PLAY_BY_PLAY)),

    /** The mean of nflverse's per-game air-yards share, from 0 to 1. */
    AIR_YARDS_SHARE("air-yards share", Figure.SHARE, "games", false,
            List.of(FeedId.WEEKLY_STATS)),

    /** The mean of nflverse's per-game weighted opportunity rating. */
    WOPR("WOPR", Figure.RATING, "games", false, List.of(FeedId.WEEKLY_STATS)),

    /** Targets on plays snapped at or inside the opponent's 20, from the play-by-play. */
    RED_ZONE_TARGETS("red-zone targets", Figure.TOTAL, "games", false,
            List.of(FeedId.PLAY_BY_PLAY)),

    /** Carries on plays snapped at or inside the opponent's 20, from the play-by-play. */
    RED_ZONE_CARRIES("red-zone carries", Figure.TOTAL, "games", false,
            List.of(FeedId.PLAY_BY_PLAY)),

    /** Targets thrown at least as far as the goal line, from the play-by-play. */
    END_ZONE_TARGETS("end-zone targets", Figure.TOTAL, "games", false,
            List.of(FeedId.PLAY_BY_PLAY)),

    /** Targets FTN charted him as the quarterback's first read on. */
    FIRST_READ_TARGETS("first-read targets", Figure.TOTAL, "games", false,
            List.of(FeedId.FTN_CHARTING, FeedId.PLAY_BY_PLAY)),

    /** The mean of what his opportunities were worth per game, in full-PPR points. */
    EXPECTED_POINTS_PER_GAME("expected fantasy points per game", Figure.AVERAGE, "games", false,
            List.of(FeedId.EXPECTED_POINTS)),

    /** PFR's yards before contact, summed. */
    YARDS_BEFORE_CONTACT("yards before contact", Figure.TOTAL, "carries", false,
            List.of(FeedId.RUSHING_ADVANCED_STATS)),

    /** PFR's yards after contact per carry. */
    YARDS_AFTER_CONTACT_PER_CARRY("yards after contact per carry", Figure.AVERAGE, "carries",
            true, List.of(FeedId.RUSHING_ADVANCED_STATS)),

    /** Tackles PFR charted him breaking on carries. */
    BROKEN_TACKLES("broken tackles", Figure.TOTAL, "carries", false,
            List.of(FeedId.RUSHING_ADVANCED_STATS));

    /** What kind of figure a stat is, which sets how its value is written. */
    public enum Figure {
        /** A count, written as a whole number. */
        TOTAL,
        /** A rate or average, written to one decimal place. */
        AVERAGE,
        /** A share from 0 to 1, written as a percentage. */
        SHARE,
        /** An index near 0 to 1 that is not a share, written to two decimal places. */
        RATING
    }

    private final String label;
    private final Figure figure;
    private final String sampleUnit;
    private final boolean perAttempt;
    private final List<FeedId> feeds;

    PlayerStat(String label, Figure figure, String sampleUnit, boolean perAttempt,
            List<FeedId> feeds) {
        this.label = label;
        this.figure = figure;
        this.sampleUnit = sampleUnit;
        this.perAttempt = perAttempt;
        this.feeds = feeds;
    }

    @Override
    public String label() {
        return label;
    }

    /** Returns {@link Direction#HIGHER_IS_WORSE} for pressured rate and better for the rest. */
    @Override
    public Direction direction() {
        return this == PRESSURED_RATE ? Direction.HIGHER_IS_WORSE : Direction.HIGHER_IS_BETTER;
    }

    @Override
    public String sampleUnit() {
        return sampleUnit;
    }

    /** Returns 0: a player with any measured value is ranked. */
    @Override
    public int minimumSample() {
        return 0;
    }

    @Override
    public boolean highestRanksFirst() {
        return true;
    }

    @Override
    public boolean percentage() {
        return figure == Figure.SHARE;
    }

    /** Returns what kind of figure the stat is. */
    public Figure figure() {
        return figure;
    }

    /**
     * Returns true when the stat is a rate over his own attempts, such as
     * yards per carry, whose rank means little without its sample.
     */
    public boolean perAttempt() {
        return perAttempt;
    }

    /** Returns true when the stat can be measured over any span of weeks, not only the season. */
    public boolean spansWeeks() {
        return this != PRESSURED_RATE;
    }

    @Override
    public List<FeedId> feeds() {
        return feeds;
    }
}
