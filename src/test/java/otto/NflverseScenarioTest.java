package otto;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.events.EventLog;
import otto.events.EventType;
import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.nflverse.NflverseFeedService;
import otto.nflverse.NflverseStore;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The nflverse feeds: an hourly timestamp check against the release
 * index, and a download only when a timestamp moves. The release assets
 * are tens of megabytes, so the timestamp is the whole point - the
 * hourly run must cost one small JSON read on an unchanged week.
 */
class NflverseScenarioTest extends WireSeamTest {

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private NflverseStore store;

    @Autowired
    private EventLog eventLog;

    private void healthyFeeds() {
        SleeperStubs.healthyInSeason(sleeper);
        NflverseStubs.healthy(nflverse);
        OutboundStubs.telegramOk(telegram);
    }

    @Test
    void theFirstRunDownloadsEverySourceAndStoresIt() {
        healthyFeeds();

        feeds.updateIfDue();

        nflverse.verify(1, getRequestedFor(urlEqualTo(NflverseStubs.STATS_2026_PATH)));
        nflverse.verify(1, getRequestedFor(urlEqualTo(NflverseStubs.DEPTH_2026_PATH)));
        nflverse.verify(1, getRequestedFor(urlEqualTo(NflverseStubs.PLAYER_IDS_PATH)));

        // Only the four positions the Player Directory keeps survive the
        // trim, and only regular-season rows: the kicker and the playoff
        // row in the fixture are dropped.
        assertThat(store.weeklyStats().orElseThrow().rows())
                .allMatch(row -> !row.position().equals("K"))
                .hasSize(19);
        // Only the newest published chart per team survives, and only
        // its offensive skill positions: the older Green Bay snapshot
        // and the defensive tackle are both dropped.
        assertThat(store.depthCharts().orElseThrow().rows()).hasSize(7);
        assertThat(store.playerIds().orElseThrow().sleeperToGsis())
                .containsEntry("5850", "00-0035700")
                .containsEntry("8138", "00-0037248")
                .doesNotContainKey("NA");
    }

    @Test
    void aRunInsideTheHourNeverTouchesTheWire() {
        healthyFeeds();
        feeds.updateIfDue();
        nflverse.resetRequests();

        clock.advance(Duration.ofMinutes(59));
        feeds.updateIfDue();

        nflverse.verify(0, getRequestedFor(urlEqualTo(NflverseStubs.STATS_RELEASE_PATH)));
        nflverse.verify(0, getRequestedFor(urlEqualTo(NflverseStubs.STATS_2026_PATH)));
    }

    @Test
    void anHourlyCheckReadsTheTimestampAndDownloadsOnlyOnChange() {
        healthyFeeds();
        feeds.updateIfDue();
        nflverse.resetRequests();

        // An hour later the release index still reports the same asset
        // timestamp: the check happens, the multi-megabyte body does not.
        clock.advance(Duration.ofHours(1));
        feeds.updateIfDue();

        nflverse.verify(1, getRequestedFor(urlEqualTo(NflverseStubs.STATS_RELEASE_PATH)));
        nflverse.verify(0, getRequestedFor(urlEqualTo(NflverseStubs.STATS_2026_PATH)));

        // The asset is republished; now the download is worth its bytes.
        NflverseStubs.weeklyStatsRepublished(nflverse);
        clock.advance(Duration.ofHours(1));
        feeds.updateIfDue();

        nflverse.verify(1, getRequestedFor(urlEqualTo(NflverseStubs.STATS_2026_PATH)));
    }

    @Test
    void aReleaseAssetIsReadThroughTheRedirectGitHubAnswersWith() {
        healthyFeeds();
        // Every GitHub release-asset URL answers 302 to the object store
        // that holds the bytes. A client that refused redirects would
        // never read a single nflverse file in production.
        nflverse.stubFor(get(urlEqualTo(NflverseStubs.STATS_2026_PATH))
                .willReturn(aResponse().withStatus(302)
                        .withHeader("Location", "/object-store/stats_player_week_2026.csv")));
        NflverseStubs.stubCsv(nflverse, "/object-store/stats_player_week_2026.csv",
                "nflverse/stats-player-week-2026.csv");

        feeds.updateIfDue();

        assertThat(store.weeklyStats().orElseThrow().rows()).hasSize(19);
    }

    @Test
    void aRenamedColumnFailsLoudlyInsteadOfPricingTheWeekAtZero() {
        healthyFeeds();
        NflverseStubs.stubCsv(nflverse, NflverseStubs.STATS_2026_PATH,
                "nflverse/stats-player-week-2026-drifted.csv");

        feeds.updateIfDue();

        // A name-keyed read survives added and reordered columns, but a
        // renamed one would read as blank and count as zero. Nothing is
        // stored, and the user hears about the blind spot.
        assertThat(store.weeklyStats()).isEmpty();
        assertThat(eventLog.all())
                .anyMatch(event -> event.type() == EventType.SOURCE_UNAVAILABLE
                        && event.key().contains("stats_player"));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("schema drift")));
    }

    @Test
    void aDownedNflverseSourceSelfReportsOnceAndLeavesTheOthersRunning() {
        healthyFeeds();
        nflverse.stubFor(get(urlEqualTo(NflverseStubs.STATS_RELEASE_PATH))
                .willReturn(aResponse().withStatus(503)));

        feeds.updateIfDue();

        assertThat(eventLog.all())
                .anyMatch(event -> event.type() == EventType.SOURCE_UNAVAILABLE
                        && event.key().contains("stats_player"));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("stats_player")));

        // The unaffected feeds still landed on this run.
        assertThat(store.depthCharts()).isPresent();
        assertThat(store.playerIds()).isPresent();

        // The same broken feed never self-reports twice.
        clock.advance(Duration.ofHours(1));
        feeds.updateIfDue();
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }
}
