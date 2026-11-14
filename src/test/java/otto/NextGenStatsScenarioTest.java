package otto;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.nflverse.Coverage.Unit;
import otto.nflverse.FeedId;
import otto.nflverse.NextGenStats;
import otto.nflverse.NflverseFeedService;
import otto.nflverse.NflverseReadiness;
import otto.nflverse.NflverseStore;
import otto.nflverse.PlayerIdMap;
import otto.nflverse.WeekStatus;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Next Gen Stats: the NFL's tracking leaderboards for passing, receiving
 * and rushing, as nflverse republishes them each week. A leaderboard
 * lists only the players who cleared the NFL's qualifying minimum, so a
 * player missing from a week is unknown, never a zero.
 */
class NextGenStatsScenarioTest extends WireSeamTest {

    private static final Instant AFTER_WEEK_3 = Instant.parse("2026-10-02T17:00:00Z");
    private static final Instant PASSING_RELEASE = Instant.parse("2026-10-01T15:01:37Z");
    private static final Instant RECEIVING_RELEASE = Instant.parse("2026-10-01T15:01:51Z");

    private static final String JORDAN_LOVE = "8135";
    private static final String PATRICK_MAHOMES = "4046";
    private static final String JUSTIN_JEFFERSON = "6794";

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private NflverseStore store;

    @Autowired
    private NflverseReadiness readiness;

    private void afterWeek3() {
        clock.set(AFTER_WEEK_3);
        SleeperStubs.healthyInSeason(sleeper);
        NflverseStubs.afterWeek3(nflverse);
        OutboundStubs.telegramOk(telegram);
        feeds.updateIfDue();
    }

    private NextGenStats passing() {
        return store.nextGenStats(NextGenStats.Kind.PASSING).orElseThrow();
    }

    private PlayerIdMap ids() {
        return store.playerIds().orElseThrow();
    }

    @Test
    void eachLeaderboardIsStoredForTheCurrentAndPreviousRegularSeason() {
        afterWeek3();

        nflverse.verify(1, getRequestedFor(urlEqualTo(NflverseStubs.NGS_PASSING_PATH)));
        nflverse.verify(1, getRequestedFor(urlEqualTo(NflverseStubs.NGS_RECEIVING_PATH)));
        nflverse.verify(1, getRequestedFor(urlEqualTo(NflverseStubs.NGS_RUSHING_PATH)));
        NextGenStats passing = passing();
        assertThat(passing.season()).isEqualTo("2026");
        assertThat(passing.assetUpdatedAt()).isEqualTo(PASSING_RELEASE);
        assertThat(passing.weeks())
                .extracting(line -> line.season() + "/" + line.week() + "/" + line.player())
                .containsExactlyInAnyOrder("2025/1/Jordan Love", "2026/1/Jordan Love",
                        "2026/1/Patrick Mahomes", "2026/3/Jordan Love");
        assertThat(passing.seasonTotals())
                .extracting(line -> line.season() + "/" + line.player())
                .containsExactlyInAnyOrder("2025/Jordan Love", "2026/Jordan Love");
        assertThat(store.nextGenStats(NextGenStats.Kind.RUSHING).orElseThrow().week("2026", 1))
                .extracting(NextGenStats.Line::player)
                .containsExactly("Bijan Robinson");
    }

    @Test
    void aSleeperPlayerReadsHisWeekAndHisSeasonTotalApart() {
        afterWeek3();

        assertThat(passing().week(ids(), JORDAN_LOVE, "2026", 1).orElseThrow().metrics())
                .containsEntry("avg_time_to_throw", 2.62)
                .containsEntry("aggressiveness", 12.1)
                .containsEntry("attempts", 33.0);
        assertThat(passing().seasonTotal(ids(), JORDAN_LOVE, "2026").orElseThrow().metrics())
                .containsEntry("attempts", 96.0);
        assertThat(passing().seasonTotal(ids(), JORDAN_LOVE, "2025").orElseThrow().metrics())
                .containsEntry("attempts", 520.0);
        assertThat(store.nextGenStats(NextGenStats.Kind.RECEIVING).orElseThrow()
                .week(ids(), JUSTIN_JEFFERSON, "2026", 1).orElseThrow().metrics())
                .containsEntry("avg_separation", 3.4)
                .containsEntry("avg_cushion", 5.9);
        assertThatThrownBy(() -> passing().week(ids(), JORDAN_LOVE, "2026", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPlayerMissingFromAWeekOrABlankMetricIsUnknownNotZero() {
        afterWeek3();

        assertThat(passing().week(ids(), JORDAN_LOVE, "2026", 2)).isEmpty();
        assertThat(passing().week(ids(), PATRICK_MAHOMES, "2026", 3)).isEmpty();
        assertThat(passing().seasonTotal(ids(), PATRICK_MAHOMES, "2026")).isEmpty();
        assertThat(passing().week(ids(), "no-such-player", "2026", 1)).isEmpty();
        assertThat(passing().week(ids(), JORDAN_LOVE, "2026", 3).orElseThrow().metrics())
                .doesNotContainKey("aggressiveness")
                .containsEntry("avg_time_to_throw", 3.01);
        assertThat(store.nextGenStats(NextGenStats.Kind.RECEIVING).orElseThrow()
                .week(ids(), JUSTIN_JEFFERSON, "2026", 2).orElseThrow().metrics())
                .doesNotContainKey("avg_yac_above_expectation")
                .containsEntry("targets", 6.0);
    }

    @Test
    void aWeekIsHeldOnceItsLeaderboardIsPublished() {
        afterWeek3();

        assertThat(readiness.weekStatus(FeedId.NGS_PASSING, "2026", 1))
                .isEqualTo(new WeekStatus.Complete(PASSING_RELEASE, List.of()));
        assertThat(readiness.weekStatus(FeedId.NGS_PASSING, "2026", 2))
                .isEqualTo(new WeekStatus.Incomplete(List.of(new Unit.Week("2026", 2)), List.of()));
        assertThat(readiness.weekStatus(FeedId.NGS_RECEIVING, "2026", 2))
                .isEqualTo(new WeekStatus.Complete(RECEIVING_RELEASE, List.of()));
        assertThat(readiness.weekStatus(FeedId.NGS_PASSING, "2026", 4))
                .isInstanceOf(WeekStatus.Unknown.class);
    }

    @Test
    void aNewSeasonDropsTheOldestSeasonFromTheSameFile() {
        afterWeek3();
        nflverse.resetRequests();

        SleeperStubs.stubJson(sleeper, SleeperStubs.STATE_PATH,
                "sleeper/state-nfl-2027-week1.json", "state-v2");
        clock.advance(Duration.ofHours(2));
        feeds.updateIfDue();

        nflverse.verify(1, getRequestedFor(urlEqualTo(NflverseStubs.NGS_PASSING_PATH)));
        assertThat(passing().season()).isEqualTo("2027");
        assertThat(passing().weeks()).allMatch(line -> line.season().equals("2026"));
        assertThat(passing().seasonTotals())
                .extracting(NextGenStats.Line::season)
                .containsExactly("2026");
    }
}
