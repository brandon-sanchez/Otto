package otto;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.nflverse.DefenseVersusPosition;
import otto.nflverse.DefenseVersusPositionBuilder;
import otto.nflverse.NflverseFeedService;
import otto.nflverse.NflverseStore;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The nightly defense-versus-position build: ESPN-style fantasy points
 * allowed per position, priced in this league's own scoring, current
 * season to date.
 *
 * The 2026 fixture holds one played week. Against DET: 120 + 40 rushing
 * yards, one rushing touchdown and 6 catches for 45 yards to running
 * backs - 32.5 points in this league. Against MIA the same positions
 * managed 13.0, against CHI 18.0 and against SEA 22.5, so DET is the
 * softest running-back matchup of the four defenses and MIA the
 * toughest. Rank 1 is always the toughest defense.
 */
class DefenseVersusPositionScenarioTest extends WireSeamTest {

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private DefenseVersusPositionBuilder builder;

    @Autowired
    private NflverseStore store;

    private void feedsReady() {
        SleeperStubs.healthyInSeason(sleeper);
        NflverseStubs.healthy(nflverse);
        OutboundStubs.telegramOk(telegram);
        feeds.updateIfDue();
    }

    @Test
    void theNightlyBuildPricesPointsAllowedPerPositionInLeagueScoring() {
        feedsReady();

        builder.build();

        DefenseVersusPosition table = store.defenseVersusPosition().orElseThrow();
        assertThat(table.basis()).isEqualTo("2026 season to date through week 1");
        assertThat(table.teams()).isEqualTo(4);

        assertThat(table.against("DET", "RB").orElseThrow().perGame())
                .isCloseTo(32.5, within(0.001));
        assertThat(table.against("DET", "RB").orElseThrow().rank()).isEqualTo(4);
        assertThat(table.against("MIA", "RB").orElseThrow().perGame())
                .isCloseTo(13.0, within(0.001));
        assertThat(table.against("MIA", "RB").orElseThrow().rank()).isEqualTo(1);
        assertThat(table.against("CHI", "RB").orElseThrow().rank()).isEqualTo(2);
        assertThat(table.against("SEA", "RB").orElseThrow().rank()).isEqualTo(3);

        // The TE premium is league scoring too: six catches for 70 yards
        // and a score is 22.0 here, not the 19.0 a generic PPR table says.
        assertThat(table.against("DET", "TE").orElseThrow().perGame())
                .isCloseTo(22.0, within(0.001));
        assertThat(table.against("DET", "QB").orElseThrow().perGame())
                .isCloseTo(32.0, within(0.001));
        assertThat(table.against("DET", "WR").orElseThrow().perGame())
                .isCloseTo(25.0, within(0.001));

        // Run defense rides alongside: rushing yards allowed per game.
        assertThat(table.runDefense("DET").orElseThrow().perGame())
                .isCloseTo(180.0, within(0.001));
        assertThat(table.runDefense("MIA").orElseThrow().rank()).isEqualTo(1);

        // Sleeper's and nflverse's own fantasy-point columns are poison in
        // the fixtures; no total may come from them.
        assertThat(table.against("DET", "RB").orElseThrow().perGame()).isNotEqualTo(99.9);
    }

    @Test
    void weekOneUsesThePriorSeasonFinalRanksAndSaysSo() {
        SleeperStubs.stubJson(sleeper, SleeperStubs.STATE_PATH,
                "sleeper/state-nfl-week1.json", "state-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH,
                "sleeper/league-in-season.json", "league-v1");
        NflverseStubs.healthy(nflverse);
        OutboundStubs.telegramOk(telegram);
        feeds.updateIfDue();

        builder.build();

        // No 2026 week has been played, so the table is last season's
        // final ranks and says so rather than reading as this season.
        DefenseVersusPosition table = store.defenseVersusPosition().orElseThrow();
        assertThat(table.basis()).isEqualTo("2025 final");
        assertThat(table.season()).isEqualTo("2025");
        assertThat(table.against("DET", "RB").orElseThrow().perGame())
                .isCloseTo(12.5, within(0.001));
        assertThat(table.against("DET", "RB").orElseThrow().rank()).isEqualTo(2);
        assertThat(table.against("MIA", "RB").orElseThrow().rank()).isEqualTo(1);
    }

    @Test
    void aFinishedSeasonIsRelabelledEvenThoughItsFileNeverMoves() {
        feedsReady();
        builder.build();
        assertThat(store.defenseVersusPosition().orElseThrow().basis())
                .isEqualTo("2026 season to date through week 1");

        // Week 1 of the next season reads the same 2026 file, and that
        // file is long since final, so its timestamp never moves again.
        // The label still has to follow the calendar.
        SleeperStubs.stubJson(sleeper, SleeperStubs.STATE_PATH,
                "sleeper/state-nfl-2027-week1.json", "state-v2");
        clock.advance(Duration.ofHours(2));
        feeds.updateIfDue();

        builder.build();

        assertThat(store.defenseVersusPosition().orElseThrow().basis()).isEqualTo("2026 final");
    }

    @Test
    void aBuildWithoutTheWeeklyStatsSaysWhatItCannotSee() {
        SleeperStubs.healthyInSeason(sleeper);
        NflverseStubs.healthy(nflverse);
        OutboundStubs.telegramOk(telegram);
        nflverse.stubFor(get(urlEqualTo(NflverseStubs.STATS_RELEASE_PATH))
                .willReturn(aResponse().withStatus(503)));
        feeds.updateIfDue();

        builder.build();

        // No weekly stats ever landed, so there is nothing to rank. The
        // job writes no table rather than an empty one, and the user
        // hears about the blind spot from the assistant.
        assertThat(store.defenseVersusPosition()).isEmpty();
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("stats_player")));
    }
}
