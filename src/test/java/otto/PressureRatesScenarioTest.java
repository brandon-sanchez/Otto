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
import otto.matchup.PressureRates;
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

    private static PressureRates.DefenseRate defense(PressureRates rates, String team) {
        return rates.defenses().stream().filter(rate -> rate.team().equals(team)).findFirst()
                .orElseThrow();
    }

    private static PressureRates.PasserRate passer(PressureRates rates, String pfrId) {
        return rates.passers().stream().filter(rate -> rate.pfrId().equals(pfrId)).findFirst()
                .orElseThrow();
    }

    private PressureRates afterWeek3() {
        clock.set(AFTER_WEEK_3);
        SleeperStubs.healthyInSeason(sleeper);
        NflverseStubs.afterWeek3(nflverse);
        NflverseStubs.pressureSeason(nflverse);
        OutboundStubs.telegramOk(telegram);
        feeds.updateIfDue();
        return PressureRates.of(store.pfrDefense().orElseThrow(), store.pfrPassing().orElseThrow(),
                store.playByPlay().orElseThrow(), store.players().orElseThrow()).orElseThrow();
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

        PressureRates.DefenseRate sanFrancisco = defense(rates, "SF");
        assertThat(sanFrancisco.games()).isEqualTo(3);
        assertThat(sanFrancisco.pressures()).isEqualTo(8 + 11 + 10);
        assertThat(sanFrancisco.opposingDropbacks()).isEqualTo(25 + 4 + 28 + 54);
        assertThat(sanFrancisco.rate()).hasValueCloseTo(29.0 / 111.0, within(1e-9));
    }

    @Test
    void aGameAgainstAPasserWhoWasNeverPressuredStillCountsHisDropbacks() {
        PressureRates rates = afterWeek3();

        PressureRates.DefenseRate chicago = defense(rates, "CHI");
        assertThat(chicago.games()).isEqualTo(1);
        assertThat(chicago.unmeasuredGames()).isZero();
        assertThat(chicago.opposingDropbacks()).isEqualTo(30 + 4);
        assertThat(chicago.rate()).hasValueCloseTo(7.0 / 34, within(1e-9));
        PressureRates.PasserRate dalton = passer(rates, "DaltAn00");
        assertThat(dalton.timesPressured()).isZero();
        assertThat(dalton.unmeasuredGames()).as("the register does not list him").isEqualTo(1);
    }

    @Test
    void aGameThePlayByPlayDoesNotHoldIsNotEstimated() {
        PressureRates rates = afterWeek3();

        PressureRates.DefenseRate cleveland = defense(rates, "CLE");
        assertThat(cleveland.games()).isZero();
        assertThat(cleveland.unmeasuredGames()).isEqualTo(1);
        assertThat(cleveland.rate()).isEmpty();
        PressureRates.DefenseRate pittsburgh = defense(rates, "PIT");
        assertThat(pittsburgh.games()).isEqualTo(1);
        assertThat(pittsburgh.unmeasuredGames()).isEqualTo(1);
    }

    @Test
    void aQuarterbacksPressuredRateIsHisPressuresOverHisPlayByPlayDropbacks() {
        PressureRates rates = afterWeek3();

        PressureRates.PasserRate purdy = passer(rates, "PurdBr00");
        assertThat(purdy.player()).isEqualTo("Brock Purdy");
        assertThat(purdy.team()).isEqualTo("SF");
        assertThat(purdy.games()).isEqualTo(3);
        assertThat(purdy.timesPressured()).isEqualTo(3 + 3 + 6);
        assertThat(purdy.dropbacks()).isEqualTo(36 + 25 + 28);
        assertThat(purdy.rate()).hasValueCloseTo(12.0 / 89, within(1e-9));
    }
}
