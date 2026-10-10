package otto.nflverse;

import java.util.List;

/**
 * The ordered registry of release-asset feeds. A new feed needs a
 * {@link FeedId}, its spec, a field and an entry in {@link #ALL} here, and
 * its document in {@link NflverseFeed}'s permits list. {@code FeedRegistryTest}
 * fails when those disagree.
 *
 * The schedule runs first, so a run that brings in a week also brings in
 * the games that say what the week should hold.
 */
final class Feeds {

    static final ScheduleFeed SCHEDULE = new ScheduleFeed();
    static final WeeklyStatsFeed WEEKLY_STATS = new WeeklyStatsFeed();
    static final SnapCountsFeed SNAP_COUNTS = new SnapCountsFeed();
    static final WeeklyRostersFeed WEEKLY_ROSTERS = new WeeklyRostersFeed();
    static final DepthChartsFeed DEPTH_CHARTS = new DepthChartsFeed();
    static final FtnChartingFeed FTN_CHARTING = new FtnChartingFeed();

    static final List<FeedSpec<?, ?>> ALL = List.of(
            SCHEDULE, WEEKLY_STATS, DEPTH_CHARTS, WEEKLY_ROSTERS, SNAP_COUNTS, FTN_CHARTING);

    private Feeds() {
    }

    static FeedSpec<?, ?> of(FeedId id) {
        return ALL.stream()
                .filter(spec -> spec.id() == id)
                .findFirst()
                .orElseThrow();
    }
}
