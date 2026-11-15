package otto;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.matchup.BlitzRates;
import otto.nflverse.NflverseFeedService;
import otto.nflverse.NflverseStore;
import otto.nflverse.PlayByPlay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.groups.Tuple.tuple;

class BlitzRatesScenarioTest extends WireSeamTest {

    private static final String HARRIS = "00-0040727";
    private static final String GADSDEN = "00-0040189";
    private static final String HERBERT = "00-0036355";

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private NflverseStore store;

    private BlitzRates rates;

    @BeforeEach
    void chargersAtSeattleOnDisk() {
        SleeperStubs.healthyInSeason(sleeper);
        NflverseStubs.chargersUsage(nflverse);
        OutboundStubs.telegramOk(telegram);

        feeds.updateIfDue();

        rates = BlitzRates.of(store.playByPlay().orElseThrow(),
                store.ftnCharting().orElseThrow(), store.players().orElseThrow()).orElseThrow();
    }

    @Test
    void aDefensesBlitzRateIsItsBlitzedDropbacksOverItsDropbacks() {
        BlitzRates.DefenseRate seattle = rates.defense("SEA").orElseThrow();

        assertThat(seattle.dropbacks()).isEqualTo(41);
        assertThat(seattle.blitzedDropbacks()).isEqualTo(10);
        assertThat(seattle.rate()).hasValueCloseTo(10.0 / 41, within(1e-9));
        assertThat(rates.defense("LAC").orElseThrow().rate())
                .hasValueCloseTo(9.0 / 26, within(1e-9));
    }

    @Test
    void sacksAndThrowawaysCountAsDropbacksThoughNobodyWasTargeted() {
        PlayByPlay playByPlay = store.playByPlay().orElseThrow();
        assertThat(playByPlay.rows()).noneMatch(play -> play.playId().equals("1243"));
        assertThat(playByPlay.dropbacks())
                .filteredOn(play -> play.playId().equals("1243"))
                .singleElement()
                .satisfies(sack -> {
                    assertThat(sack.offense()).isEqualTo("LAC");
                    assertThat(sack.defense()).isEqualTo("SEA");
                    assertThat(sack.passerId()).isEqualTo(HERBERT);
                });
    }

    @Test
    void aScrambleIsTheQuarterbacksCarryAndHisDropback() {
        PlayByPlay playByPlay = store.playByPlay().orElseThrow();

        assertThat(playByPlay.rows())
                .filteredOn(play -> play.playId().equals("1752"))
                .singleElement()
                .satisfies(carry -> assertThat(carry.kind()).isEqualTo(PlayByPlay.Kind.CARRY));
        assertThat(playByPlay.dropbacks())
                .filteredOn(play -> play.playId().equals("1752"))
                .singleElement()
                .satisfies(scramble -> {
                    assertThat(scramble.scramble()).isTrue();
                    assertThat(scramble.passerId()).isEqualTo(HERBERT);
                });
    }

    @Test
    void everyDefenseIsListedInTeamCodeOrder() {
        assertThat(rates.defenses()).extracting(BlitzRates.DefenseRate::team)
                .containsExactly("LAC", "SEA");
    }

    @Test
    void targetsAgainstTheBlitzComeFromTheTargetedReceiverOnEachBlitzedDropback() {
        BlitzRates.OffenseTargets chargers = rates.offense("LAC").orElseThrow();

        assertThat(chargers.blitzedDropbacks()).isEqualTo(10);
        assertThat(chargers.targets()).isEqualTo(8);
        assertThat(chargers.players())
                .extracting(BlitzRates.PlayerTargets::gsisId,
                        BlitzRates.PlayerTargets::position, BlitzRates.PlayerTargets::targets)
                .startsWith(tuple(HARRIS, "WR", 3), tuple(GADSDEN, "TE", 2))
                .hasSize(5);
        assertThat(chargers.byPosition()).isEqualTo(Map.of("WR", 4, "RB", 2, "TE", 2));
        assertThat(chargers.unplacedTargets()).isZero();
    }

    @Test
    void aReceiverMissingFromThePlayerRegisterIsCountedButNotGrouped() {
        BlitzRates.OffenseTargets seahawks = rates.offense("SEA").orElseThrow();

        assertThat(seahawks.targets()).isEqualTo(6);
        assertThat(seahawks.byPosition()).isEmpty();
        assertThat(seahawks.unplacedTargets()).isEqualTo(6);
        assertThat(seahawks.players()).allSatisfy(player -> assertThat(player.position()).isNull());
    }

    @Test
    void aChartedDropbackThePlayByPlayDoesNotHoldIsCountedAsUnmatched() {
        assertThat(store.ftnCharting().orElseThrow().dropbacks()).hasSize(69);
        assertThat(rates.unmatchedDropbacks())
                .as("two dropbacks a penalty wiped out")
                .isEqualTo(2);
    }
}
