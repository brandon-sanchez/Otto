package otto.nflverse;

import java.util.List;

/** The ordered registry of release-asset feeds. A new feed is one spec and one entry here. */
final class Feeds {

    static final WeeklyStatsFeed WEEKLY_STATS = new WeeklyStatsFeed();
    static final SnapCountsFeed SNAP_COUNTS = new SnapCountsFeed();
    static final WeeklyRostersFeed WEEKLY_ROSTERS = new WeeklyRostersFeed();
    static final DepthChartsFeed DEPTH_CHARTS = new DepthChartsFeed();

    static final List<FeedSpec<?, ?>> ALL = List.of(
            WEEKLY_STATS, DEPTH_CHARTS, WEEKLY_ROSTERS, SNAP_COUNTS);

    private Feeds() {
    }
}
