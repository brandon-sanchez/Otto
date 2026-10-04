package otto.nflverse;

/** Every release-asset feed Otto reads. The player-id map is an ETag download, not a feed. */
public enum FeedId {
    SCHEDULE, WEEKLY_STATS, SNAP_COUNTS, WEEKLY_ROSTERS, DEPTH_CHARTS
}
