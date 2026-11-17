package otto;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.advice.ToolAnswer;
import otto.ask.AskTools;
import otto.ask.LeagueRankLookup;
import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.matchup.MatchupSourcesFactory;
import otto.matchup.Position;
import otto.matchup.Ranking;
import otto.matchup.ScheduleStat;
import otto.matchup.ScheduleStrength;
import otto.matchup.Tone;
import otto.nflverse.DefenseVersusPositionBuilder;
import otto.nflverse.NflverseFeedService;

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
    private MatchupSourcesFactory sources;

    @Autowired
    private AskTools tools;

    private Optional<ScheduleStrength> scheduleStrength() {
        return sources.open().scheduleStrength();
    }

    private Optional<Ranking> runningBackSchedules() {
        return sources.open().rankings().schedules(ScheduleStat.REMAINING_SCHEDULE, Position.RB);
    }

    private void feedsReady() {
        SleeperStubs.healthyInSeason(sleeper);
        NflverseStubs.healthy(nflverse);
        NflverseStubs.stubCsv(nflverse, NflverseStubs.SCHEDULE_PATH,
                "nflverse/games-schedule-strength.csv");
        OutboundStubs.telegramOk(telegram);
        feeds.updateIfDue();
    }

    @Test
    void averagesWhatTheRemainingOpponentsAllow() {
        feedsReady();
        defenseBuilder.build();

        ScheduleStrength strength = scheduleStrength().orElseThrow();

        assertThat(strength.season()).isEqualTo("2026");
        assertThat(strength.basis()).isEqualTo("2026 season to date through week 1");
        assertThat(strength.firstWeek()).isEqualTo(3);
        assertThat(strength.lastWeek()).isEqualTo(5);

        // NYG: DET in week 4 and SEA in week 5; the week 3 bye adds nothing.
        ScheduleStrength.Rating giants = strength.of("NYG", "RB").orElseThrow();
        assertThat(giants.perGame()).isCloseTo(27.5, within(0.001));
        assertThat(giants.games()).isEqualTo(2);

        // MIN already played DET on Thursday of week 3, so only CHI is left.
        ScheduleStrength.Rating vikings = strength.of("MIN", "RB").orElseThrow();
        assertThat(vikings.perGame()).isCloseTo(18.0, within(0.001));
        assertThat(vikings.games()).isEqualTo(1);

        // GB's week 1 and 2 games against DET and CHI are behind it.
        ScheduleStrength.Rating packers = strength.of("GB", "RB").orElseThrow();
        assertThat(packers.perGame()).isCloseTo(17.75, within(0.001));
        assertThat(packers.games()).isEqualTo(2);

        ScheduleStrength.Rating raiders = strength.of("LV", "RB").orElseThrow();
        assertThat(raiders.perGame()).isCloseTo(44.0 / 3, within(0.001));
        assertThat(raiders.games()).isEqualTo(3);

        assertThat(strength.of("NYG", "QB").orElseThrow().games()).isEqualTo(2);
    }

    @Test
    void ranksTheHardestRemainingScheduleFirstWithItsToneAndRankFromTheBottom() {
        feedsReady();
        defenseBuilder.build();

        Ranking ranking = runningBackSchedules().orElseThrow();

        assertThat(ranking.population().describe()).isEqualTo("of 4 teams");
        assertThat(ranking.ranked()).extracting(Ranking.Placement::id)
                .containsExactly("LV", "GB", "MIN", "NYG");
        Ranking.Placement raiders = ranking.placement("LV").orElseThrow();
        assertThat(raiders.rank()).isEqualTo(1);
        assertThat(raiders.rankFromBottom()).isEqualTo(4);
        assertThat(ranking.tone(raiders)).isEqualTo(Tone.RED);
        Ranking.Placement giants = ranking.placement("NYG").orElseThrow();
        assertThat(giants.rank()).isEqualTo(4);
        assertThat(giants.rankFromBottom()).isEqualTo(1);
        assertThat(ranking.tone(giants)).isEqualTo(Tone.GREEN);
        assertThat(ranking.weeks()).isEqualTo(new Ranking.Weeks("2026", false, 3, 5));
    }

    @Test
    void getLeagueRankAnswersWhereATeamsRemainingScheduleRanks() {
        feedsReady();
        defenseBuilder.build();

        ToolAnswer<LeagueRankLookup.Answer> answer =
                tools.getLeagueRank("remaining_schedule", "RB", "Giants");

        assertThat(answer.unavailable()).isNull();
        LeagueRankLookup.Answer facts = answer.facts();
        assertThat(facts.stat())
                .isEqualTo("fantasy points per game the remaining opponents allow to running backs");
        assertThat(facts.population()).isEqualTo("of 4 teams");
        assertThat(facts.rankMeaning()).startsWith("1st is the hardest remaining schedule");
        assertThat(facts.asked().name()).isEqualTo("NYG");
        assertThat(facts.asked().rank()).isEqualTo("4th");
        assertThat(facts.asked().rankFromBottom()).isEqualTo("1st from the bottom");
        assertThat(facts.asked().matchup())
                .isEqualTo("green: an easy remaining schedule for running backs");
        assertThat(facts.asked().sample()).isEqualTo("2 remaining games");
        assertThat(facts.weeks()).isEqualTo("weeks 3 to 5 of 2026");
    }

    @Test
    void aTeamWhoseRemainingOpponentsAreNotRatedHasNoSchedule() {
        feedsReady();
        defenseBuilder.build();

        // DET's one remaining opponent, NYG, never appears as a defense
        // in the table, so there is nothing to average for DET.
        assertThat(scheduleStrength().orElseThrow().of("DET", "RB")).isEmpty();
        assertThat(runningBackSchedules().orElseThrow().placement("DET")).isEmpty();
    }

    @Test
    void nothingIsRatedBeforeTheDefenseTableIsBuilt() {
        feedsReady();

        assertThat(scheduleStrength()).isEmpty();
        assertThat(runningBackSchedules()).isEmpty();
    }
}
