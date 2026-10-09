package otto.nflverse;

import java.util.List;

/**
 * The ordered registry of release-asset feeds. A new feed needs a
 * {@link FeedId}, its spec, a field and an entry in {@link #ALL} here, and
 * its document in {@link NflverseFeed}'s permits list. {@code FeedRegistryTest}
 * fails when those disagree.
 */
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
