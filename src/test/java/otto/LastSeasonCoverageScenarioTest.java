package otto;

import java.time.Instant;
import java.util.Map;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.nflverse.FeedId;
import otto.nflverse.LastSeasonContext;
import otto.nflverse.LastSeasonFact;
import otto.nflverse.NflverseFeedService;
import otto.nflverse.NflverseStore;
import otto.nflverse.PbpParticipation;
import otto.nflverse.PbpParticipation.DefenseTendencies;
import otto.nflverse.PbpParticipation.DropbackShare;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The expected numbers were counted by hand from the trimmed fixture,
 * which keeps the real 2025 rows byte for byte.
 */
class LastSeasonCoverageScenarioTest extends WireSeamTest {

    private static final Instant AFTER_WEEK_3 = Instant.parse("2026-10-02T17:00:00Z");
    private static final String KELCE_SLEEPER_ID = "1466";
    private static final String KELCE_GSIS_ID = "00-0030506";
    private static final String CREED_HUMPHREY_CENTER_GSIS_ID = "00-0036623";

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private NflverseStore store;

    @Autowired
    private LastSeasonContext lastSeason;

    private void afterWeek3() {
        clock.set(AFTER_WEEK_3);
        SleeperStubs.healthyInSeason(sleeper);
        NflverseStubs.afterWeek3(nflverse);
        OutboundStubs.telegramOk(telegram);
    }

    private DropbackShare share(String gsisId) {
        return store.pbpParticipation().orElseThrow().players().stream()
                .filter(player -> player.gsisId().equals(gsisId))
                .findFirst().orElseThrow();
    }

    @Test
    void theCurrentSeasonReadsLastSeasonsFileAndNeverAsksForItsOwn() {
        afterWeek3();

        NflverseFeedService.Result result = feeds.updateIfDue();

        assertThat(result.feed(FeedId.PBP_PARTICIPATION))
                .isEqualTo(new NflverseFeedService.Update.Downloaded(6));
        nflverse.verify(1, getRequestedFor(urlEqualTo(NflverseStubs.PARTICIPATION_2025_PATH)));
        nflverse.verify(0, getRequestedFor(urlMatching(".*pbp_participation_2026.*")));
        PbpParticipation stored = store.pbpParticipation().orElseThrow();
        assertThat(stored.season()).isEqualTo("2025");
        assertThat(stored.assetUpdatedAt()).isEqualTo(Instant.parse("2026-02-10T18:54:08Z"));
    }

    @Test
    void eachDefenseKeepsItsManZoneShellAndPressureCountsOnDropbacks() {
        afterWeek3();

        feeds.updateIfDue();

        PbpParticipation stored = store.pbpParticipation().orElseThrow();
        // Green Bay and Chicago appear only in the playoff game, and the
        // Rams' "LA" reads as Sleeper's "LAR".
        assertThat(stored.defenses()).extracting(DefenseTendencies::team)
                .containsExactly("HOU", "KC", "LAC", "LAR", "NYG");
        DefenseTendencies kansasCity = stored.defenses().get(1);
        assertThat(kansasCity).isEqualTo(new DefenseTendencies("KC", 11, 6, 5,
                Map.of("COVER_1", 5, "COVER_2", 3, "COVER_3", 2, "2_MAN", 1), 3));
        assertThat(kansasCity.manRate()).isCloseTo(6.0 / 11, within(1e-9));
        assertThat(kansasCity.zoneRate()).isCloseTo(5.0 / 11, within(1e-9));
        assertThat(kansasCity.shellRate("COVER_2")).isCloseTo(3.0 / 11, within(1e-9));
        assertThat(kansasCity.shellRate("COVER_0")).isZero();
        assertThat(kansasCity.pressureRate()).isCloseTo(3.0 / 11, within(1e-9));
    }

    @Test
    void aPlayersShareCountsOnlyTheDropbacksOfGamesHePlayedIn() {
        afterWeek3();

        feeds.updateIfDue();

        PbpParticipation stored = store.pbpParticipation().orElseThrow();
        assertThat(stored.players()).hasSize(41);
        assertThat(share(KELCE_GSIS_ID))
                .isEqualTo(new DropbackShare(KELCE_GSIS_ID, "KC", 2, 10, 12));
        // Worthy was hurt in week 1 and missed week 3, so his share is of
        // week 1's 8 dropbacks alone.
        DropbackShare worthy = share("00-0039894");
        assertThat(worthy).isEqualTo(new DropbackShare("00-0039894", "KC", 1, 1, 8));
        assertThat(worthy.share()).isCloseTo(1.0 / 8, within(1e-9));
        assertThat(stored.players()).extracting(DropbackShare::gsisId)
                .doesNotContain(CREED_HUMPHREY_CENTER_GSIS_ID);
    }

    @Test
    void everyFactNamesLastSeasonBesideTheWeeksThisSeasonHasCompleted() {
        afterWeek3();

        feeds.updateIfDue();

        LastSeasonFact<DefenseTendencies> kansasCity = lastSeason.defense("KC").orElseThrow();
        assertThat(kansasCity.season()).isEqualTo("2025");
        assertThat(kansasCity.completedWeeksThisSeason()).isEqualTo(OptionalInt.of(3));
        assertThat(kansasCity.basis())
                .isEqualTo("last season (2025), beside 3 completed weeks of 2026");
        assertThat(kansasCity.value().dropbacks()).isEqualTo(11);

        assertThat(lastSeason.player(KELCE_SLEEPER_ID)).singleElement()
                .satisfies(fact -> {
                    assertThat(fact.value().onFieldDropbacks()).isEqualTo(10);
                    assertThat(fact.basis())
                            .isEqualTo("last season (2025), beside 3 completed weeks of 2026");
                });
        assertThat(lastSeason.defense("GB")).isEmpty();
        assertThat(lastSeason.player("no-such-player")).isEmpty();
    }

    @Test
    void beforeAnyWeekIsCompleteLastSeasonIsTheWholeRead() {
        afterWeek3();
        NflverseStubs.beforeAnyGameIsPlayed(nflverse);

        feeds.updateIfDue();

        LastSeasonFact<DefenseTendencies> kansasCity = lastSeason.defense("KC").orElseThrow();
        assertThat(kansasCity.completedWeeksThisSeason()).isEqualTo(OptionalInt.of(0));
        assertThat(kansasCity.basis())
                .isEqualTo("last season (2025), before any week of 2026 is complete");
    }

    @Test
    void aRenamedColumnSelfReportsAndStoresNothing() {
        afterWeek3();
        NflverseStubs.participationDrifted(nflverse);

        NflverseFeedService.Result result = feeds.updateIfDue();

        assertThat(result.feed(FeedId.PBP_PARTICIPATION))
                .isInstanceOf(NflverseFeedService.Update.Unavailable.class);
        assertThat(store.pbpParticipation()).isEmpty();
        assertThat(lastSeason.defense("KC")).isEmpty();
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("defense_man_zone_type")));
    }
}
