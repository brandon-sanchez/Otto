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
import otto.nflverse.NflverseFeedService;
import otto.nflverse.NflverseStore;
import otto.storage.JsonStore;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
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
