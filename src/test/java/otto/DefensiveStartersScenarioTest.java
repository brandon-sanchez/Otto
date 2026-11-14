package otto;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.directory.PlayerDirectoryService;
import otto.directory.PlayerDirectoryStore;
import otto.directory.PlayerHealth;
import otto.events.EventLog;
import otto.events.EventType;
import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.matchup.DefensiveStarters;
import otto.matchup.DefensiveStarters.Starter;
import otto.matchup.DefensiveStarters.TeamDefense;
import otto.nflverse.DefensiveGroup;
import otto.nflverse.NflverseFeedService;
import otto.nflverse.NflverseStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class DefensiveStartersScenarioTest extends WireSeamTest {

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private PlayerDirectoryService directory;

    @Autowired
    private PlayerDirectoryStore directoryStore;

    @Autowired
    private NflverseStore store;

    @Autowired
    private DefensiveStarters starters;

    @Autowired
    private EventLog eventLog;

    private void aWeekWithInjuredDefenders(String depthCharts) {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-defense.json", "players-defense");
        NflverseStubs.healthy(nflverse);
        NflverseStubs.stubCsv(nflverse, NflverseStubs.DEPTH_2026_PATH, depthCharts);
        NflverseStubs.stubCsv(nflverse, NflverseStubs.PLAYER_IDS_PATH,
                "nflverse/db-playerids-defense.csv");
        OutboundStubs.telegramOk(telegram);
        directory.refreshNow();
        feeds.updateIfDue();
    }

    private TeamDefense defenseOf(String team) {
        return ((DefensiveStarters.Answer.Known) starters.forTeam(team)).defense();
    }

    @Test
    void allFourOfTheStartingCornersAreListedWhenEachIsOutOrDoubtful() {
        aWeekWithInjuredDefenders("nflverse/depth-charts-defense.csv");

        TeamDefense patriots = defenseOf("NE");

        assertThat(patriots.chartedAt()).isEqualTo(Instant.parse("2026-09-15T07:30:00Z"));
        assertThat(patriots.outOrDoubtful(DefensiveGroup.CORNERBACK))
                .extracting(Starter::player, Starter::place, Starter::health)
                .containsExactly(
                        tuple("Carlton Davis III", "LCB1", PlayerHealth.OUT),
                        tuple("Charles Woods", "LCB2", PlayerHealth.IR),
                        tuple("Christian Gonzalez", "RCB1", PlayerHealth.DOUBTFUL),
                        tuple("Channing Canada", "RCB2", PlayerHealth.OUT));
        assertThat(patriots.starters(DefensiveGroup.CORNERBACK))
                .extracting(Starter::place)
                .containsExactly("LCB1", "LCB2", "NB1", "RCB1", "RCB2");
    }

    @Test
    void eachStarterLandsInTheGroupHisSchemePlaysHimIn() {
        aWeekWithInjuredDefenders("nflverse/depth-charts-defense.csv");

        TeamDefense patriots = defenseOf("NE");
        TeamDefense bills = defenseOf("BUF");

        assertThat(patriots.outOrDoubtful(DefensiveGroup.EDGE))
                .extracting(Starter::place).containsExactly("WLB2");
        assertThat(patriots.outOrDoubtful(DefensiveGroup.INTERIOR_LINE))
                .extracting(Starter::place).containsExactly("LDE1");
        assertThat(patriots.outOrDoubtful(DefensiveGroup.SAFETY))
                .extracting(Starter::place).containsExactly("SS1");
        assertThat(patriots.outOrDoubtful(DefensiveGroup.LINEBACKER)).isEmpty();
        assertThat(bills.outOrDoubtful(DefensiveGroup.EDGE))
                .extracting(Starter::place).containsExactly("LDE1");
        assertThat(bills.outOrDoubtful(DefensiveGroup.LINEBACKER))
                .extracting(Starter::place).containsExactly("WLB1");
        assertThat(bills.outOrDoubtful(DefensiveGroup.INTERIOR_LINE)).isEmpty();
    }

    @Test
    void aStarterWithNoSleeperJoinIsReportedUnseenRatherThanHealthy() {
        aWeekWithInjuredDefenders("nflverse/depth-charts-defense.csv");

        TeamDefense patriots = defenseOf("NE");

        assertThat(patriots.unseen())
                .extracting(Starter::player, Starter::place)
                .containsExactly(tuple("Kevin Byard", "FS1"));
        assertThat(patriots.starters(DefensiveGroup.SAFETY))
                .filteredOn(starter -> starter.place().equals("FS1"))
                .singleElement()
                .extracting(Starter::health).isNull();
    }

    @Test
    void theChartKeepsDefendersBesideTheOffenseAndDropsSpecialTeams() {
        aWeekWithInjuredDefenders("nflverse/depth-charts-defense.csv");

        assertThat(store.depthCharts().orElseThrow().rows())
                .filteredOn(spot -> spot.team().equals("NE"))
                .extracting(spot -> spot.label())
                .contains("QB1", "LCB1", "LCB3", "WLB2", "LDE1")
                .doesNotContain("PK1");
    }

    @Test
    void defendersNeverEnterThePlayersALookupSearches() {
        aWeekWithInjuredDefenders("nflverse/depth-charts-defense.csv");

        assertThat(directoryStore.read().orElseThrow().defenders())
                .containsKeys("9002", "9010")
                .doesNotContainKeys("11564", "9020", "9021");
        assertThat(directoryStore.read().orElseThrow().players())
                .containsKey("11564")
                .doesNotContainKeys("9002", "9010");
    }

    @Test
    void aTeamWithNoDefensiveChartIsUnavailableWithAReason() {
        aWeekWithInjuredDefenders("nflverse/depth-charts-defense.csv");

        assertThat(starters.forTeam("KC"))
                .isInstanceOfSatisfying(DefensiveStarters.Answer.Unavailable.class,
                        unavailable -> assertThat(unavailable.reason())
                                .contains("KC").contains("depth chart"));
    }

    @Test
    void aFormationThisCodeCannotPlaceFailsTheDownloadAsDrift() {
        aWeekWithInjuredDefenders("nflverse/depth-charts-defense-drifted-formation.csv");

        assertThat(store.depthCharts()).isEmpty();
        assertThat(eventLog.all())
                .anyMatch(event -> event.type() == EventType.SOURCE_UNAVAILABLE
                        && event.key().contains("depth_charts"));
        assertThat(starters.forTeam("NE"))
                .isInstanceOf(DefensiveStarters.Answer.Unavailable.class);
    }
}
