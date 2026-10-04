package otto.nflverse;

import java.time.Instant;
import java.util.List;

/**
 * What the hourly refresh needs to know about any stored nflverse
 * feed, whatever it holds: which season's file it came from, the
 * publish timestamp it was taken at, and when it was last checked.
 * Those three answer the only question the refresh asks - is the
 * stored copy still the current one? The rows let an unchanged copy be
 * stored again under a new checked-at time without a per-feed copier.
 *
 * @param <R> one stored row
 */
public sealed interface NflverseFeed<R>
        permits Schedule, WeeklyStats, DepthCharts, WeeklyRosters, SnapCounts {

    String season();

    Instant assetUpdatedAt();

    Instant checkedAt();

    List<R> rows();
}
