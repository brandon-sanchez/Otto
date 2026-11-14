package otto;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.nflverse.FeedId;
import otto.nflverse.NflverseFeedService;
import otto.nflverse.NflverseReadiness;
import otto.nflverse.NflverseStore;
import otto.nflverse.PressureRates;
import otto.nflverse.WeekStatus;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PressureRatesScenarioTest extends WireSeamTest {

    private static final Instant AFTER_WEEK_3 = Instant.parse("2026-10-02T17:00:00Z");
    private static final Instant DEFENSE_RELEASE = Instant.parse("2026-10-01T12:03:04Z");
    private static final Instant PASSING_RELEASE = Instant.parse("2026-10-01T12:02:53Z");

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private NflverseStore store;

    @Autowired
    private NflverseReadiness readiness;

    private PressureRates afterWeek3() {
        clock.set(AFTER_WEEK_3);
        SleeperStubs.healthyInSeason(sleeper);
        NflverseStubs.afterWeek3(nflverse);
        OutboundStubs.telegramOk(telegram);
        feeds.updateIfDue();
        return PressureRates.of(store.pfrDefense().orElseThrow(), store.pfrPassing().orElseThrow())
                .orElseThrow();
    }

    @Test
    void bothAdvancedStatsFilesAreStoredThroughTheFeedEngine() {
        afterWeek3();

        nflverse.verify(1, getRequestedFor(urlEqualTo(NflverseStubs.PFR_DEFENSE_2026_PATH)));
        nflverse.verify(1, getRequestedFor(urlEqualTo(NflverseStubs.PFR_PASSING_2026_PATH)));
        assertThat(readiness.weekStatus(FeedId.PFR_DEFENSE, "2026", 3))
                .isEqualTo(new WeekStatus.Complete(DEFENSE_RELEASE, List.of()));
        assertThat(readiness.weekStatus(FeedId.PFR_PASSING, "2026", 3))
                .isEqualTo(new WeekStatus.Complete(PASSING_RELEASE, List.of()));
    }

    @Test
    void aDefensesRateIsItsPlayersPressuresOverTheOpposingDropbacks() {
        PressureRates rates = afterWeek3();

        PressureRates.DefenseRate sanFrancisco = rates.defense("SF").orElseThrow();
        assertThat(sanFrancisco.games()).isEqualTo(3);
        assertThat(sanFrancisco.pressures()).isEqualTo(8 + 11 + 10);
        assertThat(sanFrancisco.opposingDropbacks()).isEqualTo(25 + 4 + 28 + 54);
        assertThat(sanFrancisco.rate()).hasValueCloseTo(29.0 / 111.0, within(1e-9));
    }

    @Test
    void aGameAgainstAPasserWhoseDropbacksAreUnknownIsNotEstimated() {
        PressureRates rates = afterWeek3();

        assertThat(store.pfrPassing().orElseThrow().rows())
                .filteredOn(line -> line.pfrId().equals("DaltAn00"))
                .singleElement()
                .satisfies(dalton -> assertThat(dalton.dropbacks()).isEmpty());
        PressureRates.DefenseRate chicago = rates.defense("CHI").orElseThrow();
        assertThat(chicago.games()).isZero();
        assertThat(chicago.unmeasuredGames()).isEqualTo(1);
        assertThat(chicago.rate()).isEmpty();
    }

    @Test
    void defensesAreOrderedFromTheMostPressureToTheLeast() {
        PressureRates rates = afterWeek3();

        List<PressureRates.DefenseRate> ranked = rates.defenses();
        assertThat(ranked.getFirst().team()).isEqualTo("MIN");
        assertThat(ranked.getLast().team()).isEqualTo("CHI");
        assertThat(ranked).extracting(PressureRates.DefenseRate::team).contains("LAR");
    }

    @Test
    void aQuarterbacksPressuredRateComesFromTimesPressuredPct() {
        PressureRates rates = afterWeek3();

        PressureRates.PasserRate purdy = rates.passer("PurdBr00").orElseThrow();
        assertThat(purdy.player()).isEqualTo("Brock Purdy");
        assertThat(purdy.team()).isEqualTo("SF");
        assertThat(purdy.games()).isEqualTo(3);
        assertThat(purdy.timesPressured()).isEqualTo(3 + 3 + 6);
        assertThat(purdy.dropbacks()).isEqualTo(36 + 25 + 28);
        assertThat(purdy.rate()).hasValueCloseTo(12.0 / 89, within(1e-9));
        assertThat(store.pfrPassing().orElseThrow().rows())
                .filteredOn(line -> line.pfrId().equals("PurdBr00"))
                .extracting(line -> line.timesPressuredPct())
                .containsExactly(0.083, 0.12, 0.214);
    }
}
