package otto.nflverse;

import java.util.List;

/**
 * The ordered registry of release-asset feeds. A new feed is one spec and
 * one entry here. The schedule runs first, so a run that brings in a
 * week also brings in the games that say what the week should hold.
 */
final class Feeds {

    static final ScheduleFeed SCHEDULE = new ScheduleFeed();
    static final WeeklyStatsFeed WEEKLY_STATS = new WeeklyStatsFeed();
    static final SnapCountsFeed SNAP_COUNTS = new SnapCountsFeed();
    static final WeeklyRostersFeed WEEKLY_ROSTERS = new WeeklyRostersFeed();
    static final DepthChartsFeed DEPTH_CHARTS = new DepthChartsFeed();

    static final List<FeedSpec<?, ?>> ALL = List.of(
            SCHEDULE, WEEKLY_STATS, DEPTH_CHARTS, WEEKLY_ROSTERS, SNAP_COUNTS);

    private Feeds() {
    }

    static FeedSpec<?, ?> of(FeedId id) {
        return ALL.stream()
                .filter(spec -> spec.id() == id)
                .findFirst()
                .orElseThrow();
    }
}
