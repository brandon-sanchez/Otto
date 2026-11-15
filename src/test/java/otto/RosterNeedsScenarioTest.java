package otto;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.ask.UserWeekLoader;
import otto.check.CheckRunner;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.needs.RosterNeeds;
import otto.needs.RosterNeeds.SidelineReason;
import otto.needs.RosterNeeds.SlotStanding;
import otto.needs.RosterNeedsService;
import otto.sleeper.SourceResult;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Roster needs in a three-team Superflex league in 2026 week 3, with
 * the starting slots QB, RB, WR, TE, FLEX and SUPER_FLEX, two bench
 * spots, IR and a taxi squad, in PPR with a tight-end premium.
 *
 * <p>The user holds seven active players for eight active spots, so
 * one IR or taxi player can come up. Quentin Bye is on bye, Reed
 * Sidelined is Out, and Ty Injured is still on Injured Reserve. Ray
 * Healed (IR) and Wade Rookie (taxi) are healthy. Wes First scored
 * 30 for another team in week 1 and joined the user for week 2.
 */
class RosterNeedsScenarioTest extends WireSeamTest {

    private static final String QUENTIN_BYE = "20002";
    private static final String REED_SIDELINED = "20004";
    private static final String RAY_HEALED = "20008";
    private static final String TY_INJURED = "20009";
    private static final String WADE_ROOKIE = "20010";
    private static final String WALT_HURT = "20011";

    /** The TE premium is half a point per catch, so six catches earn three more. */
    private static final double TATE_PRIME_PROJECTED = 6 + 20 * 0.1 + 6 * 0.5;

    /** Drew Dropped's 5 in week 1, then Wes First's 10 in week 2. */
    private static final double USER_WR_AVERAGE = (5.0 + 10.0) / 2;

    private static final String MATCHUPS_WEEK_1 = SleeperStubs.LEAGUE_PATH + "/matchups/1";
    private static final String MATCHUPS_WEEK_2 = SleeperStubs.LEAGUE_PATH + "/matchups/2";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private UserWeekLoader loader;

    @Autowired
    private RosterNeedsService needs;

    private void aWeekThreeLeague(String rostersFixture) {
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-needs.json", "players-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH,
                "sleeper/league-needs.json", "league-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH, rostersFixture, "rosters-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.USERS_PATH, "sleeper/users.json", "users-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.STATE_PATH,
                "sleeper/state-nfl-week3.json", "state-v1");
        SleeperStubs.stubJson(sleeper, "/v1/projections/nfl/regular/2026/3",
                "sleeper/projections-needs-2026-3.json", "projections-v1");
        SleeperStubs.stubJson(sleeper, "/scores/nfl/regular/2026/3",
                "sleeper/scores-needs-2026-3.json", "scores-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH + "/transactions/2",
                "sleeper/transactions-none.json", "transactions-w2");
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH + "/transactions/3",
                "sleeper/transactions-none.json", "transactions-w3");
        SleeperStubs.stubJson(sleeper, MATCHUPS_WEEK_1, "sleeper/matchups-needs-1.json",
                "matchups-w1");
        SleeperStubs.stubJson(sleeper, MATCHUPS_WEEK_2, "sleeper/matchups-needs-2.json",
                "matchups-w2");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "A lineup alert.");
        checkRunner.runCheck();
    }

    private RosterNeeds assess() {
        return switch (loader.leagueWeek()) {
            case SourceResult.Ok<otto.ask.LeagueWeek> ok -> switch (needs.assess(ok.value())) {
                case SourceResult.Ok<RosterNeeds> assessed -> assessed.value();
                case SourceResult.Unavailable<RosterNeeds> unavailable ->
                    throw new AssertionError("needs unavailable: " + unavailable.reason());
            };
            case SourceResult.Unavailable<otto.ask.LeagueWeek> unavailable ->
                throw new AssertionError("league unavailable: " + unavailable.reason());
        };
    }

    private static SlotStanding slot(List<SlotStanding> standings, String name) {
        return standings.stream()
                .filter(standing -> standing.slot().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void byesInjuriesAndFlexEligibilityAreReadTogetherWithOneActivationOfCover() {
        aWeekThreeLeague("sleeper/rosters-needs.json");

        RosterNeeds.Coverage coverage = assess().coverage();

        assertThat(coverage.openSlots()).isEmpty();
        assertThat(coverage.activations()).containsExactly(WADE_ROOKIE);
        assertThat(coverage.sidelined())
                .extracting(RosterNeeds.Sidelined::playerId, RosterNeeds.Sidelined::reason)
                .containsExactlyInAnyOrder(
                        tuple(QUENTIN_BYE, SidelineReason.BYE),
                        tuple(REED_SIDELINED, SidelineReason.DESIGNATION),
                        tuple(TY_INJURED, SidelineReason.DESIGNATION));
    }

    @Test
    void anIrOrTaxiPlayerIsNoCoverWhenTheActiveRosterHasNoRoomForHim() {
        aWeekThreeLeague("sleeper/rosters-needs-full.json");

        RosterNeeds.Coverage coverage = assess().coverage();

        assertThat(coverage.openSlots()).containsExactly("SUPER_FLEX");
        assertThat(coverage.positionsThatFill()).containsExactlyInAnyOrder("QB", "RB", "WR", "TE");
        assertThat(coverage.activations()).isEmpty();
        assertThat(coverage.sidelined())
                .extracting(RosterNeeds.Sidelined::playerId, RosterNeeds.Sidelined::reason)
                .contains(
                        tuple(RAY_HEALED, SidelineReason.NO_ROOM_TO_ACTIVATE),
                        tuple(WADE_ROOKIE, SidelineReason.NO_ROOM_TO_ACTIVATE),
                        tuple(WALT_HURT, SidelineReason.DESIGNATION));
    }

    @Test
    void aSlotOnlyOnePositionCanFillNamesThatPositionAlone() {
        aWeekThreeLeague("sleeper/rosters-needs-no-tight-end.json");

        RosterNeeds.Coverage coverage = assess().coverage();

        assertThat(coverage.openSlots()).containsExactly("TE");
        assertThat(coverage.positionsThatFill()).containsExactly("TE");
        assertThat(coverage.activations()).containsExactly(WADE_ROOKIE);
    }

    @Test
    void weaknessRanksEachStartingSlotAgainstTheOtherRostersInLeagueScoring() {
        aWeekThreeLeague("sleeper/rosters-needs.json");

        List<SlotStanding> weakness = assess().weakness();

        assertThat(slot(weakness, "TE").points()).contains(TATE_PRIME_PROJECTED);
        assertThat(slot(weakness, "TE").rank()).contains(1);
        assertThat(slot(weakness, "SUPER_FLEX").points()).contains(6.0);
        assertThat(slot(weakness, "SUPER_FLEX").rank()).contains(3);
        assertThat(slot(weakness, "SUPER_FLEX").leagueMedian()).contains(12.0);
        assertThat(slot(weakness, "SUPER_FLEX").rankedTeams()).isEqualTo(3);
        assertThat(slot(weakness, "QB").rank()).contains(2);
    }

    @Test
    void pastScoringCountsOnlyThePointsTheUsersOwnStartersScoredForHim() {
        aWeekThreeLeague("sleeper/rosters-needs.json");

        RosterNeeds assessed = assess();

        assertThat(assessed.weeksScored()).isEqualTo(2);
        SlotStanding receiver = slot(assessed.pastScoring(), "WR");
        assertThat(receiver.points()).contains(USER_WR_AVERAGE);
        assertThat(receiver.rank()).contains(3);
        assertThat(receiver.leagueMedian()).contains(17.0);
        assertThat(slot(assessed.pastScoring(), "SUPER_FLEX").points()).contains(14.0);
        assertThat(slot(assessed.pastScoring(), "SUPER_FLEX").rank()).contains(2);
    }

    @Test
    void aStoredWeekOutlivesSleeperAndTheLatestWeekIsReadAgainForCorrections() {
        aWeekThreeLeague("sleeper/rosters-needs.json");
        assess();

        sleeper.stubFor(get(urlEqualTo(MATCHUPS_WEEK_1)).willReturn(aResponse().withStatus(500)));
        SleeperStubs.stubJson(sleeper, MATCHUPS_WEEK_2, "sleeper/matchups-needs-2-corrected.json",
                "matchups-w2-corrected");
        clock.advance(Duration.ofHours(1));

        RosterNeeds assessed = assess();

        assertThat(assessed.weeksScored()).isEqualTo(2);
        assertThat(slot(assessed.pastScoring(), "WR").points()).contains((5.0 + 12.0) / 2);
        assertThat(assessed.notes()).isEmpty();
    }

    @Test
    void aWeekSleeperCannotServeAndNobodyStoredIsSaidRatherThanScoredAsZero() {
        aWeekThreeLeague("sleeper/rosters-needs.json");
        sleeper.stubFor(get(urlEqualTo(MATCHUPS_WEEK_1)).willReturn(aResponse().withStatus(500)));

        RosterNeeds assessed = assess();

        assertThat(assessed.weeksScored()).isEqualTo(1);
        assertThat(slot(assessed.pastScoring(), "WR").points()).contains(10.0);
        assertThat(assessed.notes())
                .anyMatch(note -> note.startsWith("I could not read week 1's starters"));
    }

    @Test
    void withoutProjectionsWeaknessIsUnknownRatherThanZero() {
        aWeekThreeLeague("sleeper/rosters-needs.json");
        sleeper.stubFor(get(urlEqualTo("/v1/projections/nfl/regular/2026/3"))
                .willReturn(aResponse().withStatus(500)));
        clock.advance(Duration.ofHours(1));

        RosterNeeds assessed = assess();

        assertThat(assessed.weakness()).isEmpty();
        assertThat(assessed.notes()).anyMatch(note -> note.contains("no projections"));
        assertThat(assessed.coverage().openSlots()).isEmpty();
        assertThat(assessed.weeksScored()).isEqualTo(2);
    }
}
