package otto.nflverse;

import java.time.Instant;

/**
 * What the hourly refresh needs to know about any stored nflverse
 * feed, whatever it holds: which season's file it came from, the
 * publish timestamp it was taken at, and when it was last checked.
 * Those three answer the only question the refresh asks - is the
 * stored copy still the current one? A document that keeps rows
 * declares them itself; one that keeps only coverage has none to read.
 */
public sealed interface NflverseFeed
        permits Schedule, WeeklyStats, DepthCharts, WeeklyRosters, SnapCounts, FtnCharting {

    String season();

    Instant assetUpdatedAt();

    Instant checkedAt();

    /** Null only on a copy stored before coverage existed, which the refresh downloads again. */
    Coverage coverage();
}
