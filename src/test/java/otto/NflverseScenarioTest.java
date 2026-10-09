package otto;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.events.EventLog;
import otto.events.EventType;
import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.nflverse.DepthCharts;
import otto.nflverse.FeedId;
import otto.nflverse.NflverseFeedService;
import otto.nflverse.NflverseStore;
import otto.nflverse.PlayerIdMap;
import otto.nflverse.Schedule;

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
        nflverse.verify(1, getRequestedFor(urlEqualTo(NflverseStubs.SNAPS_2026_PATH)));
        nflverse.verify(1, getRequestedFor(urlEqualTo(NflverseStubs.SCHEDULE_PATH)));
        nflverse.verify(1, getRequestedFor(urlEqualTo(NflverseStubs.FTN_2026_PATH)));

        // The schedule file carries every season since 1999; only the
        // regular season of this season and the last one is kept.
        Schedule schedule = store.schedule().orElseThrow();
        assertThat(schedule.rows()).hasSize(21)
                .allMatch(game -> game.season().equals("2026") || game.season().equals("2025"))
                .noneMatch(game -> game.gameId().equals("2025_19_GB_CHI"));
        assertThat(schedule.rows()).filteredOn(game -> game.gameId().equals("2026_01_SF_LA"))
                .singleElement()
                .isEqualTo(new Schedule.Game("2026_01_SF_LA", "2026", 1,
                        Instant.parse("2026-09-11T00:35:00Z"), "LAR", "SF", true));
        assertThat(schedule.rows()).filteredOn(game -> game.gameId().equals("2026_04_NE_BUF"))
                .singleElement()
                .extracting(Schedule.Game::finished).isEqualTo(false);

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
        PlayerIdMap ids = store.playerIds().orElseThrow();
        assertThat(ids.gsisFor("5850")).contains("00-0035700");
        assertThat(ids.gsisFor("8138")).contains("00-0037248");
        assertThat(ids.gsisFor("NA")).isEmpty();
        assertThat(ids.pfrFor("4034")).contains("McCaCh01");
        assertThat(store.snapCounts().orElseThrow().rows())
                .extracting(row -> row.pfrId() + "=" + row.offensePct())
                .contains("NacuPu00=0.78", "WillKy02=0.65", "AkerCa00=0.35");
    }

    @Test
    void aFinishedSeasonsSnapCountsAreRelabelledEvenThoughTheFileNeverMoves() {
        healthyFeeds();
        feeds.updateIfDue();
        assertThat(store.snapCounts().orElseThrow().priorSeasonFinal()).isFalse();
        nflverse.resetRequests();

        // Week 1 of the next season reads the same, long-final 2026 file.
        // RoleShares drops last season's shares by this flag, so it has to
        // follow the calendar even though nothing is downloaded.
        SleeperStubs.stubJson(sleeper, SleeperStubs.STATE_PATH,
                "sleeper/state-nfl-2027-week1.json", "state-v2");
        clock.advance(Duration.ofHours(2));
        feeds.updateIfDue();

        nflverse.verify(0, getRequestedFor(urlEqualTo(NflverseStubs.SNAPS_2026_PATH)));
        assertThat(store.snapCounts().orElseThrow().season()).isEqualTo("2026");
        assertThat(store.snapCounts().orElseThrow().priorSeasonFinal()).isTrue();
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
    void aDepthChartDateThisCodeCannotReadFailsInsteadOfSortingAsText() {
        healthyFeeds();
        // Day-first dates sort as text into an order the calendar does
        // not agree with, which would hand the newest chart's rank to
        // the wrong snapshot - and the rank a player held on the chart
        // before this one is what a promotion is read from.
        NflverseStubs.stubCsv(nflverse, NflverseStubs.DEPTH_2026_PATH,
                "nflverse/depth-charts-2026-drifted-date.csv");

        feeds.updateIfDue();

        assertThat(store.depthCharts()).isEmpty();
        assertThat(eventLog.all())
                .anyMatch(event -> event.type() == EventType.SOURCE_UNAVAILABLE
                        && event.key().contains("depth_charts"));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("schema drift")));

        // The feeds that still parse are unaffected.
        assertThat(store.weeklyStats()).isPresent();
        assertThat(store.playerIds()).isPresent();
    }

    @Test
    void aChartPublishedWithAnOffsetOrdersAgainstOneWrittenWithAZ() {
        healthyFeeds();
        NflverseStubs.stubCsv(nflverse, NflverseStubs.DEPTH_2026_PATH,
                "nflverse/depth-charts-2026-mixed-formats.csv");

        feeds.updateIfDue();

        // The three rows name the same three moments whichever way they
        // are written, so the newest chart is still the newest one and
        // Jacobs still reads as promoted from RB2.
        DepthCharts.Spot jacobs = store.depthCharts().orElseThrow().rows().stream()
                .filter(spot -> spot.gsisId().equals("00-0035700"))
                .findFirst().orElseThrow();
        assertThat(jacobs.rank()).isEqualTo(1);
        assertThat(jacobs.previousRank()).isEqualTo(2);
        assertThat(jacobs.promoted()).isTrue();
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

    @Test
    void aDownedSnapFeedDoesNotFailTheBoardFeeds() {
        healthyFeeds();
        nflverse.stubFor(get(urlEqualTo(NflverseStubs.SNAPS_RELEASE_PATH))
                .willReturn(aResponse().withStatus(503)));

        NflverseFeedService.Result result = feeds.updateIfDue();

        assertThat(result.feed(FeedId.SNAP_COUNTS)).isInstanceOf(NflverseFeedService.Update.Unavailable.class);
        assertThat(store.snapCounts()).isEmpty();
        assertThat(store.weeklyStats()).isPresent();
        assertThat(store.depthCharts()).isPresent();
        assertThat(store.playerIds()).isPresent();
    }
}
