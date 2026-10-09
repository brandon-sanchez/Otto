package otto.nflverse;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each spec's fingerprint over one fixed row, pinned. A fingerprint moves
 * only when the content a spec measures changes shape, and then every
 * stored unit would read as corrected unless the spec's
 * {@code contentVersion} is bumped with it. The pin turns that
 * remember-to-bump convention into a failing build.
 */
class FingerprintPinTest {

    private static final Instant KICKOFF = Instant.parse("2026-09-11T00:35:00Z");
    private static final Instant CHARTED_AT = Instant.parse("2026-09-30T07:30:00Z");
    private static final Instant PULLED_AT = Instant.parse("2026-09-28T17:01:50.896745Z");

    @Test
    void scheduleContent() {
        pin(Feeds.SCHEDULE, 1, 6398640526969526180L,
                new Schedule.Game("2026_01_SF_LA", "2026", 1, KICKOFF, "LAR", "SF", true));
    }

    @Test
    void weeklyStatsContent() {
        pin(Feeds.WEEKLY_STATS, 1, -4954135196257438353L,
                new WeeklyStats.StatLine("00-0041021", "Running Back SF", "RB", "SF", "LAR", 1,
                        0.22, Map.of("rush_att", 13.0, "rush_yd", 53.0)));
    }

    @Test
    void snapCountsContent() {
        pin(Feeds.SNAP_COUNTS, 1, -3172303126358366611L,
                new SnapCounts.SnapLine("RbSF00", "RB", 1, 0.62, "2026_01_SF_LA"));
    }

    @Test
    void weeklyRostersContent() {
        pin(Feeds.WEEKLY_ROSTERS, 1, -745542852120132812L,
                new WeeklyRosters.Standing("00-0041021", 1, "A01", "SF"));
    }

    @Test
    void depthChartsContent() {
        pin(Feeds.DEPTH_CHARTS, 1, -3244690983820737895L,
                new DepthCharts.Spot("00-0041021", "Running Back SF", "SF", "RB", 1, 2,
                        CHARTED_AT));
    }

    @Test
    void ftnChartingContent() {
        pin(Feeds.FTN_CHARTING, 1, -3431614496028758167L,
                new FtnCharting.Play("2026_01_SF_LA", 1, "40", PULLED_AT));
    }

    private static <R> void pin(FeedSpec<R, ?> spec, int contentVersion, long fingerprint,
            R row) {
        assertThat(spec.contentVersion())
                .as("%s contentVersion: bump it with the pinned fingerprint", spec.id())
                .isEqualTo(contentVersion);
        assertThat(Coverage.fingerprint(Coverage.sha256(), spec.content(row)))
                .as("%s content changed shape: bump %s.contentVersion() past %d so stored"
                        + " fingerprints are replaced instead of read as corrections, then re-pin"
                        + " both values here", spec.id(), spec.getClass().getSimpleName(),
                        spec.contentVersion())
                .isEqualTo(fingerprint);
    }
}
