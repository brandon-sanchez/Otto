package otto;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.nflverse.Coverage;
import otto.nflverse.FeedId;
import otto.nflverse.NflverseFeedService;
import otto.nflverse.NflverseReadiness;
import otto.nflverse.NflverseStore;
import otto.nflverse.WeekStatus;
import otto.storage.JsonStore;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * What Otto knows about each nflverse feed beyond its file timestamp:
 * which weeks and games it holds, and which of them changed after Otto
 * first saw them. nflverse republishes a whole season file at once, so
 * a correction has to be found in the content, one unit at a time.
 */
class NflverseFreshnessScenarioTest extends WireSeamTest {

    private static final Instant FIRST_RELEASE = Instant.parse("2026-09-16T07:10:12Z");
    private static final Instant CORRECTED_RELEASE = Instant.parse("2026-09-23T07:12:40Z");

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private NflverseStore store;

    @Autowired
    private NflverseReadiness readiness;

    @Autowired
    private JsonStore jsonStore;

    private void afterWeek3() {
        SleeperStubs.healthyInSeason(sleeper);
        NflverseStubs.afterWeek3(nflverse);
        OutboundStubs.telegramOk(telegram);
        feeds.updateIfDue();
    }

    private void nextHour() {
        clock.advance(Duration.ofHours(1));
        feeds.updateIfDue();
    }

    private Coverage.UnitRecord statsUnit(int week, String team) {
        return store.weeklyStats().orElseThrow().coverage().units().stream()
                .filter(record -> record.unit().equals(new Coverage.Unit(week, team)))
                .findFirst().orElseThrow();
    }

    private List<Coverage.Unit> correctedStatsUnits() {
        return store.weeklyStats().orElseThrow().coverage().units().stream()
                .filter(record -> record.corrections() > 0)
                .map(Coverage.UnitRecord::unit)
                .toList();
    }

    @Test
    void aRepublishWithChangedNumbersRecordsACorrectionForThatUnitOnly() {
        afterWeek3();

        NflverseStubs.weeklyStatsCorrected(nflverse);
        nextHour();

        assertThat(correctedStatsUnits()).containsExactly(new Coverage.Unit(2, "SF"));
        Coverage.UnitRecord corrected = statsUnit(2, "SF");
        assertThat(corrected.firstSeenAt()).isEqualTo(FIRST_RELEASE);
        assertThat(corrected.changedAt()).isEqualTo(CORRECTED_RELEASE);
        assertThat(corrected.corrections()).isEqualTo(1);
        assertThat(statsUnit(2, "MIA").changedAt()).isEqualTo(FIRST_RELEASE);
        assertThat(statsUnit(1, "SF").changedAt()).isEqualTo(FIRST_RELEASE);
        assertThat(readiness.weekStatus(FeedId.WEEKLY_STATS, "2026", 2))
                .isEqualTo(new WeekStatus.Complete(CORRECTED_RELEASE, List.of("SF")));
        assertThat(readiness.weekStatus(FeedId.WEEKLY_STATS, "2026", 1))
                .isEqualTo(new WeekStatus.Complete(FIRST_RELEASE, List.of()));
    }

    @Test
    void theLatestCompletedWeekComesFromTheScheduleNotTheCalendar() {
        afterWeek3();

        // Sleeper says week 2 and week 4's Thursday game is final; only
        // week 3 has every game over.
        assertThat(readiness.latestCompletedWeek("2026")).hasValue(3);
    }

    @Test
    void aWeekIsCompleteOnlyWhenEveryFinalGameHasRows() {
        afterWeek3();

        assertThat(readiness.weekStatus(FeedId.WEEKLY_STATS, "2026", 3))
                .isEqualTo(new WeekStatus.Incomplete(List.of("ARI"), List.of()));
        assertThat(readiness.weekStatus(FeedId.WEEKLY_STATS, "2026", 2))
                .isEqualTo(new WeekStatus.Complete(FIRST_RELEASE, List.of()));
        // The snap fixture's games are not this schedule's, so a week of
        // rows still holds none of the games the week expects.
        assertThat(readiness.weekStatus(FeedId.SNAP_COUNTS, "2026", 1))
                .isEqualTo(new WeekStatus.Incomplete(List.of("2026_01_SF_LA", "2026_01_ATL_PIT",
                        "2026_01_BUF_HOU", "2026_01_GB_MIN"), List.of()));
        assertThat(readiness.weekStatus(FeedId.WEEKLY_STATS, "2026", 9))
                .isInstanceOf(WeekStatus.Unknown.class);
        assertThat(readiness.weekStatus(FeedId.WEEKLY_STATS, "2025", 1))
                .isInstanceOf(WeekStatus.Unknown.class);
    }

    @Test
    void rostersAreDueAtKickoffNotAtTheFinalWhistle() {
        afterWeek3();

        // Only Thursday's game is final, yet the rosters owe every team
        // of the week, and the stats owe only Thursday's two.
        assertThat(readiness.weekStatus(FeedId.WEEKLY_ROSTERS, "2026", 4))
                .isEqualTo(new WeekStatus.Incomplete(List.of("NO"), List.of()));
        assertThat(readiness.weekStatus(FeedId.WEEKLY_ROSTERS, "2026", 3))
                .isInstanceOf(WeekStatus.Complete.class);
        assertThat(readiness.weekStatus(FeedId.WEEKLY_STATS, "2026", 4))
                .isEqualTo(new WeekStatus.Incomplete(List.of("PIT", "CLE"), List.of()));
    }

    @Test
    void aDepthChartCountsForTheWeekItsDateFallsIn() {
        afterWeek3();

        assertThat(readiness.weekStatus(FeedId.DEPTH_CHARTS, "2026", 4))
                .isEqualTo(new WeekStatus.Incomplete(List.of("NO"), List.of()));
        assertThat(readiness.weekStatus(FeedId.DEPTH_CHARTS, "2026", 3))
                .isInstanceOf(WeekStatus.Complete.class);
    }

    @Test
    void aMissingScheduleMakesEveryAnswerUnknownNotIncomplete() {
        SleeperStubs.healthyInSeason(sleeper);
        NflverseStubs.afterWeek3(nflverse);
        NflverseStubs.scheduleUnavailable(nflverse);
        OutboundStubs.telegramOk(telegram);

        feeds.updateIfDue();

        assertThat(store.weeklyStats()).isPresent();
        for (FeedId feed : FeedId.values()) {
            for (int week = 1; week <= 4; week++) {
                assertThat(readiness.weekStatus(feed, "2026", week))
                        .as("%s week %d", feed, week)
                        .isInstanceOf(WeekStatus.Unknown.class);
            }
        }
        assertThat(readiness.latestCompletedWeek("2026")).isEmpty();
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("schedules")));
    }

    @Test
    void aRepublishWithTheSameNumbersInADifferentOrderIsNotACorrection() {
        afterWeek3();
        List<Coverage.UnitRecord> before = store.weeklyStats().orElseThrow().coverage().units();

        NflverseStubs.weeklyStatsReordered(nflverse);
        nextHour();

        nflverse.verify(2, getRequestedFor(urlEqualTo(NflverseStubs.STATS_2026_PATH)));
        assertThat(correctedStatsUnits()).isEmpty();
        assertThat(store.weeklyStats().orElseThrow().coverage().units())
                .extracting(Coverage.UnitRecord::unit, Coverage.UnitRecord::fingerprint,
                        Coverage.UnitRecord::changedAt)
                .isEqualTo(before.stream()
                        .map(record -> tuple(record.unit(), record.fingerprint(), FIRST_RELEASE))
                        .toList());
    }

    @Test
    void theSameReleaseIngestedTwiceCountsOneCorrection() {
        afterWeek3();
        NflverseStubs.weeklyStatsCorrected(nflverse);
        nextHour();

        nextHour();
        NflverseStubs.weeklyStatsRepublishedAgain(nflverse);
        nextHour();

        nflverse.verify(3, getRequestedFor(urlEqualTo(NflverseStubs.STATS_2026_PATH)));
        assertThat(correctedStatsUnits()).containsExactly(new Coverage.Unit(2, "SF"));
        assertThat(statsUnit(2, "SF").corrections()).isEqualTo(1);
        assertThat(statsUnit(2, "SF").changedAt()).isEqualTo(CORRECTED_RELEASE);
    }

    @Test
    void aStoredDocumentFromBeforeCoverageIsDownloadedOnce() {
        SleeperStubs.healthyInSeason(sleeper);
        NflverseStubs.afterWeek3(nflverse);
        OutboundStubs.telegramOk(telegram);
        jsonStore.write("nflverse-weekly-stats", Map.of(
                "season", "2026",
                "priorSeasonFinal", false,
                "assetUpdatedAt", FIRST_RELEASE.toString(),
                "checkedAt", TEST_START.minus(Duration.ofHours(2)).toString(),
                "rows", List.of()));

        feeds.updateIfDue();

        nflverse.verify(1, getRequestedFor(urlEqualTo(NflverseStubs.STATS_2026_PATH)));
        assertThat(store.weeklyStats().orElseThrow().coverage().units()).isNotEmpty();
        assertThat(correctedStatsUnits()).isEmpty();

        nextHour();

        nflverse.verify(1, getRequestedFor(urlEqualTo(NflverseStubs.STATS_2026_PATH)));
    }
}
