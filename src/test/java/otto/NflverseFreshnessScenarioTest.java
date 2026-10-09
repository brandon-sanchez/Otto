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
import otto.nflverse.Coverage.Unit;
import otto.nflverse.FeedId;
import otto.nflverse.FtnCharting;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * What Otto knows about each nflverse feed beyond its file timestamp:
 * which weeks and games it holds, and which of them changed after Otto
 * first saw them. nflverse republishes a whole season file at once, so
 * a correction has to be found in the content, one unit at a time.
 */
class NflverseFreshnessScenarioTest extends WireSeamTest {

    private static final Instant AFTER_WEEK_3 = Instant.parse("2026-10-02T17:00:00Z");
    private static final Instant FIRST_RELEASE = Instant.parse("2026-09-16T07:10:12Z");
    private static final Instant CORRECTED_RELEASE = Instant.parse("2026-09-23T07:12:40Z");
    private static final Instant RETURNED_RELEASE = Instant.parse("2026-09-24T07:11:58Z");
    private static final Instant ROSTERS_RELEASE = Instant.parse("2026-09-16T07:11:03Z");
    private static final Instant SNAPS_RELEASE = Instant.parse("2026-09-16T07:11:03Z");
    private static final Instant DEPTH_RELEASE = Instant.parse("2026-09-15T07:43:44Z");
    private static final Instant DEPTH_REFRESHED_RELEASE = Instant.parse("2026-10-01T07:41:09Z");
    private static final Instant FTN_RELEASE = Instant.parse("2026-10-03T11:01:21Z");
    private static final Instant FTN_RE_PULL_RELEASE = Instant.parse("2026-10-05T17:02:14Z");
    private static final Instant FTN_FIRST_PULL = Instant.parse("2026-09-28T17:01:50.896745Z");
    private static final Instant FTN_WEEK_3_PULL = Instant.parse("2026-09-30T17:01:22.925940Z");
    private static final Instant FTN_RE_PULL = Instant.parse("2026-10-05T17:02:11.104322Z");
    private static final Instant CHART_OF_SEPTEMBER_30 = Instant.parse("2026-09-30T07:30:00Z");
    private static final Instant CHART_OF_OCTOBER_1 = Instant.parse("2026-10-01T07:30:00Z");

    private static final Unit SF_WEEK_2 = new Unit.TeamWeek(2, "SF");

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private NflverseStore store;

    @Autowired
    private NflverseReadiness readiness;

    @Autowired
    private JsonStore jsonStore;

    private void afterWeek3() {
        clock.set(AFTER_WEEK_3);
        SleeperStubs.healthyInSeason(sleeper);
        NflverseStubs.afterWeek3(nflverse);
        OutboundStubs.telegramOk(telegram);
        feeds.updateIfDue();
    }

    private void nextHour() {
        clock.advance(Duration.ofHours(1));
        feeds.updateIfDue();
    }

    private static Coverage.UnitRecord unit(Coverage coverage, Unit unit) {
        return coverage.units().stream()
                .filter(record -> record.unit().equals(unit))
                .findFirst().orElseThrow();
    }

    private Coverage.UnitRecord statsUnit(int week, String team) {
        return unit(store.weeklyStats().orElseThrow().coverage(), new Unit.TeamWeek(week, team));
    }

    private Coverage.UnitRecord ftnUnit(int week, String gameId) {
        return unit(store.ftnCharting().orElseThrow().coverage(), new Unit.Game(week, gameId));
    }

    private Coverage.UnitRecord chartUnit(String team) {
        return unit(store.depthCharts().orElseThrow().coverage(), new Unit.Team(team));
    }

    private List<Unit> correctedStatsUnits() {
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

        assertThat(correctedStatsUnits()).containsExactly(SF_WEEK_2);
        Coverage.UnitRecord corrected = statsUnit(2, "SF");
        assertThat(corrected.firstSeenAt()).isEqualTo(FIRST_RELEASE);
        assertThat(corrected.changedAt()).isEqualTo(CORRECTED_RELEASE);
        assertThat(corrected.corrections()).isEqualTo(1);
        assertThat(statsUnit(2, "MIA").changedAt()).isEqualTo(FIRST_RELEASE);
        assertThat(statsUnit(1, "SF").changedAt()).isEqualTo(FIRST_RELEASE);
        assertThat(readiness.weekStatus(FeedId.WEEKLY_STATS, "2026", 2))
                .isEqualTo(new WeekStatus.Complete(CORRECTED_RELEASE, List.of(SF_WEEK_2)));
        assertThat(readiness.weekStatus(FeedId.WEEKLY_STATS, "2026", 1))
                .isEqualTo(new WeekStatus.Complete(FIRST_RELEASE, List.of()));
    }

    @Test
    void theLatestCompletedWeekComesFromTheScheduleNotTheCalendar() {
        afterWeek3();

        assertThat(readiness.latestCompletedWeek("2026")).hasValue(3);
    }

    @Test
    void aWeekIsCompleteOnlyWhenEveryFinalGameHasRows() {
        afterWeek3();

        assertThat(readiness.weekStatus(FeedId.WEEKLY_STATS, "2026", 3))
                .isEqualTo(new WeekStatus.Incomplete(List.of(new Unit.TeamWeek(3, "ARI")), List.of()));
        assertThat(readiness.weekStatus(FeedId.WEEKLY_STATS, "2026", 2))
                .isEqualTo(new WeekStatus.Complete(FIRST_RELEASE, List.of()));
        assertThat(readiness.weekStatus(FeedId.SNAP_COUNTS, "2026", 1))
                .isEqualTo(new WeekStatus.Complete(SNAPS_RELEASE, List.of()));
        assertThat(readiness.weekStatus(FeedId.WEEKLY_STATS, "2026", 9))
                .isEqualTo(new WeekStatus.Unknown(
                        "the schedule published 2026-10-04T07:46:40Z lists no 2026 week 9 games"));
        assertThat(readiness.weekStatus(FeedId.WEEKLY_STATS, "2025", 1))
                .isEqualTo(new WeekStatus.Unknown("WEEKLY_STATS holds the 2026 season, not 2025"));
    }

    @Test
    void aHalfPlayedWeekIsUnknownEvenWhenEveryFinishedGameIsHeld() {
        afterWeek3();

        assertThat(statsUnit(4, "PIT").held()).isTrue();
        assertThat(readiness.weekStatus(FeedId.WEEKLY_STATS, "2026", 4))
                .isEqualTo(new WeekStatus.Unknown("2026 week 4 is in progress: 4 of 5 games have"
                        + " no result in the schedule published 2026-10-04T07:46:40Z"));
        assertThat(readiness.weekStatus(FeedId.SNAP_COUNTS, "2026", 4))
                .isInstanceOf(WeekStatus.Unknown.class);
        assertThat(readiness.weekStatus(FeedId.FTN_CHARTING, "2026", 4))
                .isInstanceOf(WeekStatus.Unknown.class);
    }

    @Test
    void rostersAreDueAtKickoffNotAtTheFinalWhistle() {
        afterWeek3();

        assertThat(readiness.weekStatus(FeedId.WEEKLY_ROSTERS, "2026", 4))
                .isEqualTo(new WeekStatus.Incomplete(List.of(new Unit.TeamWeek(4, "NO")), List.of()));
        assertThat(readiness.weekStatus(FeedId.WEEKLY_ROSTERS, "2026", 3))
                .isEqualTo(new WeekStatus.Complete(ROSTERS_RELEASE, List.of()));
    }

    @Test
    void aWeekThatHasNotStartedOwesNothingYet() {
        afterWeek3();

        assertThat(readiness.weekStatus(FeedId.WEEKLY_ROSTERS, "2026", 5))
                .isEqualTo(new WeekStatus.Unknown(
                        "2026 week 5 has not started: it opens at 2026-10-06T04:00:00Z"));
        assertThat(readiness.weekStatus(FeedId.DEPTH_CHARTS, "2026", 5))
                .isInstanceOf(WeekStatus.Unknown.class);

        clock.set(Instant.parse("2026-10-06T12:00:00Z"));

        assertThat(readiness.weekStatus(FeedId.WEEKLY_ROSTERS, "2026", 5))
                .isEqualTo(new WeekStatus.Complete(ROSTERS_RELEASE, List.of()));
        assertThat(readiness.weekStatus(FeedId.DEPTH_CHARTS, "2026", 5))
                .isEqualTo(new WeekStatus.Incomplete(List.of(new Unit.Team("TB"),
                        new Unit.Team("DAL"), new Unit.Team("SF"), new Unit.Team("SEA")),
                        List.of()));
    }

    @Test
    void aDepthChartCountsForTheWeekItsDateFallsIn() {
        afterWeek3();

        assertThat(readiness.weekStatus(FeedId.DEPTH_CHARTS, "2026", 4))
                .isEqualTo(new WeekStatus.Incomplete(List.of(new Unit.Team("NO")), List.of()));
        assertThat(readiness.weekStatus(FeedId.DEPTH_CHARTS, "2026", 3))
                .isEqualTo(new WeekStatus.Unknown(
                        "8 of 8 teams' 2026 week 3 charts have been replaced by newer ones"));
    }

    @Test
    void aNewerDepthChartIsNewDataNotACorrection() {
        afterWeek3();

        NflverseStubs.depthChartsNewerForNewOrleans(nflverse);
        nextHour();

        nflverse.verify(2, getRequestedFor(urlEqualTo(NflverseStubs.DEPTH_2026_PATH)));
        Coverage.UnitRecord newOrleans = chartUnit("NO");
        assertThat(newOrleans.publishedAt()).isEqualTo(CHART_OF_OCTOBER_1);
        assertThat(newOrleans.corrections()).isZero();
        assertThat(newOrleans.firstSeenAt()).isEqualTo(DEPTH_REFRESHED_RELEASE);
        assertThat(chartUnit("ATL").firstSeenAt()).isEqualTo(DEPTH_RELEASE);
        assertThat(store.depthCharts().orElseThrow().rows())
                .filteredOn(spot -> spot.team().equals("GB"))
                .singleElement()
                .matches(spot -> spot.promoted() && spot.previousRank() == 2);
        assertThat(chartUnit("GB").corrections()).isZero();
        assertThat(readiness.weekStatus(FeedId.DEPTH_CHARTS, "2026", 4))
                .isEqualTo(new WeekStatus.Complete(DEPTH_REFRESHED_RELEASE, List.of()));
    }

    @Test
    void theSameDepthChartRepublishedWithOtherRanksIsACorrection() {
        afterWeek3();

        NflverseStubs.depthChartsRerankedForAtlanta(nflverse);
        nextHour();

        Coverage.UnitRecord atlanta = chartUnit("ATL");
        assertThat(atlanta.publishedAt()).isEqualTo(CHART_OF_SEPTEMBER_30);
        assertThat(atlanta.corrections()).isEqualTo(1);
        assertThat(atlanta.changedAt()).isEqualTo(DEPTH_REFRESHED_RELEASE);
        assertThat(chartUnit("GB").corrections()).isZero();
        assertThat(readiness.weekStatus(FeedId.DEPTH_CHARTS, "2026", 4))
                .isEqualTo(new WeekStatus.Incomplete(List.of(new Unit.Team("NO")),
                        List.of(new Unit.Team("ATL"))));
    }

    @Test
    void ftnDatePulledIsStoredPerGameWithoutKeepingPlays() {
        afterWeek3();

        FtnCharting charting = store.ftnCharting().orElseThrow();
        assertThat(charting.coverage().units())
                .extracting(Coverage.UnitRecord::unit, Coverage.UnitRecord::publishedAt)
                .contains(
                        tuple(new Unit.Game(1, "2026_01_SF_LA"), FTN_FIRST_PULL),
                        tuple(new Unit.Game(3, "2026_03_ATL_GB"), FTN_WEEK_3_PULL),
                        tuple(new Unit.Game(4, "2026_04_PIT_CLE"),
                                Instant.parse("2026-10-03T11:01:17.672216Z")))
                .hasSize(13);
        assertThat(jsonStore.read("nflverse-ftn-charting", Map.class).orElseThrow())
                .doesNotContainKey("rows");
        assertThat(readiness.weekStatus(FeedId.FTN_CHARTING, "2026", 3))
                .isEqualTo(new WeekStatus.Complete(FTN_RELEASE, List.of()));
    }

    @Test
    void anFtnBulkRePullMovesDatePulledButIsNotACorrection() {
        afterWeek3();

        NflverseStubs.ftnRePulled(nflverse);
        nextHour();

        nflverse.verify(2, getRequestedFor(urlEqualTo(NflverseStubs.FTN_2026_PATH)));
        Coverage.UnitRecord rePulled = ftnUnit(1, "2026_01_SF_LA");
        assertThat(rePulled.publishedAt()).isEqualTo(FTN_RE_PULL);
        assertThat(rePulled.corrections()).isZero();
        assertThat(rePulled.changedAt()).isEqualTo(FTN_RELEASE);
        assertThat(readiness.weekStatus(FeedId.FTN_CHARTING, "2026", 1))
                .isEqualTo(new WeekStatus.Complete(FTN_RELEASE, List.of()));
        assertThat(readiness.weekStatus(FeedId.FTN_CHARTING, "2026", 3))
                .isEqualTo(new WeekStatus.Complete(FTN_RE_PULL_RELEASE,
                        List.of(new Unit.Game(3, "2026_03_ATL_GB"))));
        assertThat(ftnUnit(3, "2026_03_ATL_GB").publishedAt()).isEqualTo(FTN_WEEK_3_PULL);
    }

    @Test
    void aMissingScheduleMakesEveryAnswerUnknownNotIncomplete() {
        clock.set(AFTER_WEEK_3);
        SleeperStubs.healthyInSeason(sleeper);
        NflverseStubs.afterWeek3(nflverse);
        NflverseStubs.scheduleUnavailable(nflverse);
        OutboundStubs.telegramOk(telegram);

        feeds.updateIfDue();

        assertThat(store.weeklyStats()).isPresent();
        for (int week = 1; week <= 4; week++) {
            Map<FeedId, WeekStatus> statuses = readiness.weekStatuses("2026", week);
            assertThat(statuses).as("week %d", week)
                    .doesNotContainKey(FeedId.SCHEDULE)
                    .hasSize(FeedId.values().length - 1);
            assertThat(statuses.values()).allMatch(WeekStatus.Unknown.class::isInstance);
            assertThat(readiness.weekStatus(FeedId.WEEKLY_STATS, "2026", week))
                    .isInstanceOf(WeekStatus.Unknown.class);
        }
        assertThat(readiness.latestCompletedWeek("2026")).isEmpty();
        assertThatThrownBy(() -> readiness.weekStatus(FeedId.SCHEDULE, "2026", 1))
                .isInstanceOf(IllegalArgumentException.class);
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("schedules")));
    }

    @Test
    void everyFeedsAnswerForAWeekComesFromOneReadPerDocument() {
        afterWeek3();

        assertThat(readiness.weekStatuses("2026", 3)).containsOnly(
                Map.entry(FeedId.WEEKLY_STATS,
                        new WeekStatus.Incomplete(List.of(new Unit.TeamWeek(3, "ARI")), List.of())),
                Map.entry(FeedId.SNAP_COUNTS, new WeekStatus.Complete(SNAPS_RELEASE, List.of())),
                Map.entry(FeedId.WEEKLY_ROSTERS,
                        new WeekStatus.Complete(ROSTERS_RELEASE, List.of())),
                Map.entry(FeedId.DEPTH_CHARTS, new WeekStatus.Unknown(
                        "8 of 8 teams' 2026 week 3 charts have been replaced by newer ones")),
                Map.entry(FeedId.FTN_CHARTING, new WeekStatus.Complete(FTN_RELEASE, List.of())));
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
        assertThat(correctedStatsUnits()).containsExactly(SF_WEEK_2);
        assertThat(statsUnit(2, "SF").corrections()).isEqualTo(1);
        assertThat(statsUnit(2, "SF").changedAt()).isEqualTo(CORRECTED_RELEASE);
    }

    @Test
    void aUnitThatLeavesTheFileAndReturnsIsMeasuredAgainstWhatOttoSawBefore() {
        afterWeek3();

        NflverseStubs.weeklyStatsWithoutSanFranciscoWeek2(nflverse);
        nextHour();

        assertThat(readiness.weekStatus(FeedId.WEEKLY_STATS, "2026", 2))
                .isEqualTo(new WeekStatus.Incomplete(List.of(SF_WEEK_2), List.of()));
        assertThat(statsUnit(2, "SF").held()).isFalse();
        assertThat(statsUnit(2, "SF").firstSeenAt()).isEqualTo(FIRST_RELEASE);

        NflverseStubs.weeklyStatsSanFranciscoWeek2ReturnsCorrected(nflverse);
        nextHour();

        Coverage.UnitRecord returned = statsUnit(2, "SF");
        assertThat(returned.held()).isTrue();
        assertThat(returned.firstSeenAt()).isEqualTo(FIRST_RELEASE);
        assertThat(returned.changedAt()).isEqualTo(RETURNED_RELEASE);
        assertThat(returned.corrections()).isEqualTo(1);
        assertThat(readiness.weekStatus(FeedId.WEEKLY_STATS, "2026", 2))
                .isEqualTo(new WeekStatus.Complete(RETURNED_RELEASE, List.of(SF_WEEK_2)));
    }

    @Test
    void theCurrentSeasonsFileIsReadOnceTheScheduleShowsAFinalGameWhateverSleeperSays() {
        clock.set(AFTER_WEEK_3);
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.STATE_PATH,
                "sleeper/state-nfl-week1.json", "state-week1");
        NflverseStubs.afterWeek3(nflverse);
        OutboundStubs.telegramOk(telegram);

        feeds.updateIfDue();

        nflverse.verify(1, getRequestedFor(urlEqualTo(NflverseStubs.STATS_2026_PATH)));
        nflverse.verify(0, getRequestedFor(urlEqualTo(NflverseStubs.STATS_2025_PATH)));
        nflverse.verify(1, getRequestedFor(urlEqualTo(NflverseStubs.SNAPS_2026_PATH)));
        assertThat(store.weeklyStats().orElseThrow().priorSeasonFinal()).isFalse();
    }

    @Test
    void aStoredDocumentFromBeforeCoverageIsDownloadedOnce() {
        clock.set(AFTER_WEEK_3);
        SleeperStubs.healthyInSeason(sleeper);
        NflverseStubs.afterWeek3(nflverse);
        OutboundStubs.telegramOk(telegram);
        jsonStore.write("nflverse-weekly-stats", Map.of(
                "season", "2026",
                "priorSeasonFinal", false,
                "assetUpdatedAt", FIRST_RELEASE.toString(),
                "checkedAt", AFTER_WEEK_3.minus(Duration.ofHours(2)).toString(),
                "rows", List.of()));

        feeds.updateIfDue();

        nflverse.verify(1, getRequestedFor(urlEqualTo(NflverseStubs.STATS_2026_PATH)));
        assertThat(store.weeklyStats().orElseThrow().coverage().units()).isNotEmpty();
        assertThat(correctedStatsUnits()).isEmpty();

        nextHour();

        nflverse.verify(1, getRequestedFor(urlEqualTo(NflverseStubs.STATS_2026_PATH)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aStoredDocumentAtAnOlderContentVersionIsDownloadedOnceWithoutCorrections() {
        afterWeek3();
        Map<String, Object> document = jsonStore.read("nflverse-weekly-stats", Map.class)
                .orElseThrow();
        Map<String, Object> coverage = (Map<String, Object>) document.get("coverage");
        coverage.put("contentVersion", 0);
        ((List<Map<String, Object>>) coverage.get("units"))
                .forEach(record -> record.put("fingerprint", 0L));
        jsonStore.write("nflverse-weekly-stats", document);

        nextHour();

        nflverse.verify(2, getRequestedFor(urlEqualTo(NflverseStubs.STATS_2026_PATH)));
        assertThat(correctedStatsUnits()).isEmpty();
        assertThat(store.weeklyStats().orElseThrow().coverage().contentVersion()).isEqualTo(1);
        assertThat(statsUnit(2, "SF").fingerprint()).isNotZero();
        assertThat(statsUnit(2, "SF").firstSeenAt()).isEqualTo(FIRST_RELEASE);

        nextHour();

        nflverse.verify(2, getRequestedFor(urlEqualTo(NflverseStubs.STATS_2026_PATH)));
    }
}
