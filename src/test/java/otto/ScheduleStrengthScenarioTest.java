package otto;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.nflverse.DefenseVersusPositionBuilder;
import otto.nflverse.NflverseFeedService;
import otto.nflverse.ScheduleStrength;
import otto.nflverse.ScheduleStrengthService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The defenses in the table are DET, MIA, CHI and SEA, which allow
 * 32.5, 13.0, 18.0 and 22.5 points a game to running backs in this
 * league. The schedule fixture has GB, MIN, LV and NYG play them:
 * weeks 1 and 2 are final, and so is Thursday's MIN at DET in week 3.
 * MIN has a bye in week 4 and NYG one in week 3.
 */
class ScheduleStrengthScenarioTest extends WireSeamTest {

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private DefenseVersusPositionBuilder defenseBuilder;

    @Autowired
    private ScheduleStrengthService scheduleStrength;

    private void feedsReady() {
        SleeperStubs.healthyInSeason(sleeper);
        NflverseStubs.healthy(nflverse);
        NflverseStubs.stubCsv(nflverse, NflverseStubs.SCHEDULE_PATH,
                "nflverse/games-schedule-strength.csv");
        OutboundStubs.telegramOk(telegram);
        feeds.updateIfDue();
    }

    @Test
    void averagesWhatTheRemainingOpponentsAllowAndRanksTheTeams() {
        feedsReady();
        defenseBuilder.build();

        ScheduleStrength strength = scheduleStrength.current().orElseThrow();

        assertThat(strength.season()).isEqualTo("2026");
        assertThat(strength.basis()).isEqualTo("2026 season to date through week 1");

        // NYG: DET in week 4 and SEA in week 5; the week 3 bye adds nothing.
        ScheduleStrength.Rating giants = strength.of("NYG", "RB").orElseThrow();
        assertThat(giants.perGame()).isCloseTo(27.5, within(0.001));
        assertThat(giants.games()).isEqualTo(2);
        assertThat(giants.rank()).isEqualTo(1);
        assertThat(giants.teams()).isEqualTo(4);

        // MIN already played DET on Thursday of week 3, so only CHI is left.
        ScheduleStrength.Rating vikings = strength.of("MIN", "RB").orElseThrow();
        assertThat(vikings.perGame()).isCloseTo(18.0, within(0.001));
        assertThat(vikings.games()).isEqualTo(1);
        assertThat(vikings.rank()).isEqualTo(2);

        // GB's week 1 and 2 games against DET and CHI are behind it.
        ScheduleStrength.Rating packers = strength.of("GB", "RB").orElseThrow();
        assertThat(packers.perGame()).isCloseTo(17.75, within(0.001));
        assertThat(packers.games()).isEqualTo(2);
        assertThat(packers.rank()).isEqualTo(3);

        ScheduleStrength.Rating raiders = strength.of("LV", "RB").orElseThrow();
        assertThat(raiders.perGame()).isCloseTo(44.0 / 3, within(0.001));
        assertThat(raiders.games()).isEqualTo(3);
        assertThat(raiders.rank()).isEqualTo(4);
        assertThat(raiders.teams()).isEqualTo(4);

        assertThat(strength.of("NYG", "QB").orElseThrow().games()).isEqualTo(2);
        assertThat(strength.of("NYG", "TE").orElseThrow().teams()).isEqualTo(4);
    }

    @Test
    void aTeamWhoseRemainingOpponentsAreNotRatedHasNoSchedule() {
        feedsReady();
        defenseBuilder.build();

        // DET's one remaining opponent, NYG, never appears as a defense
        // in the table, so there is nothing to average for DET.
        assertThat(scheduleStrength.current().orElseThrow().of("DET", "RB")).isEmpty();
    }

    @Test
    void nothingIsRatedBeforeTheDefenseTableIsBuilt() {
        feedsReady();

        assertThat(scheduleStrength.current()).isEmpty();
    }
}
