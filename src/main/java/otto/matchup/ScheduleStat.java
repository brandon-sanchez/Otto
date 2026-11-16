package otto.matchup;

import java.util.List;

import otto.nflverse.FeedId;

/**
 * The team stats Otto ranks for a position over the rest of the regular
 * season. Every team is ranked from one remaining game up.
 */
public enum ScheduleStat implements RankedStat {

    /**
     * The fantasy points per game a team's remaining opponents allow to
     * the position, from {@code ScheduleStrength}. Rank 1 is the hardest
     * remaining schedule, the one whose opponents allow the fewest.
     */
    REMAINING_SCHEDULE("fantasy points per game the remaining opponents allow",
            List.of(FeedId.SCHEDULE, FeedId.WEEKLY_STATS));

    private final String label;
    private final List<FeedId> feeds;

    ScheduleStat(String label, List<FeedId> feeds) {
        this.label = label;
        this.feeds = feeds;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public Direction direction() {
        return Direction.HIGHER_IS_BETTER;
    }

    @Override
    public String sampleUnit() {
        return "remaining games";
    }

    @Override
    public int minimumSample() {
        return 1;
    }

    @Override
    public boolean highestRanksFirst() {
        return false;
    }

    @Override
    public boolean percentage() {
        return false;
    }

    @Override
    public List<FeedId> feeds() {
        return feeds;
    }
}
