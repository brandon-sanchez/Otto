package otto.nflverse;

import java.time.Instant;

/**
 * What the hourly refresh needs to know about any stored nflverse
 * feed, whatever it holds: which season's file it came from, the
 * publish timestamp it was taken at, and when it was last checked.
 * Those three answer the only question the refresh asks - is the
 * stored copy still the current one?
 */
public sealed interface NflverseFeed permits WeeklyStats, DepthCharts, WeeklyRosters {

    String season();

    Instant assetUpdatedAt();

    Instant checkedAt();
}
