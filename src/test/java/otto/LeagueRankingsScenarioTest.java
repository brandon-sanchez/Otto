package otto;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.matchup.DefenseStat;
import otto.matchup.Direction;
import otto.matchup.LeagueRankings;
import otto.matchup.MatchupSourcesFactory;
import otto.matchup.PlayerStat;
import otto.matchup.Position;
import otto.matchup.Ranking;
import otto.nflverse.DefenseVersusPositionBuilder;
import otto.nflverse.NflverseFeedService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

/**
 * League ranks read from the stored nflverse feeds.
 *
 * The rankings weekly stats hold five tight ends over weeks 1 to 3:
 * Philadelphia's caught 11 targets in his one game, San Francisco's 24
 * in three, Buffalo's and Atlanta's 18 each, and Green Bay's 9. A wide
 * receiver plays alongside them. The rankings register places the PFR
 * ball carriers and passers at their positions, and a punter it lists
 * is dropped by the register feed, as every non-skill position is.
 */
class LeagueRankingsScenarioTest extends WireSeamTest {

    private static final String PHI_TE = "00-0042005";
    private static final String SF_TE = "00-0042001";
    private static final String BUF_TE = "00-0042002";
    private static final String ATL_TE = "00-0042003";
    private static final String GB_TE = "00-0042004";
    private static final String GADSDEN = "00-0040189";
    private static final String BARKLEY = "00-0034844";
    private static final String JACOBS = "00-0033856";
    private static final String GUERENDO = "00-0039146";
    private static final String LOVE = "00-0036264";
    private static final String PURDY = "00-0036389";
    private static final String RUSH = "00-0035542";

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private DefenseVersusPositionBuilder defenseBuilder;

    @Autowired
    private MatchupSourcesFactory sources;

    private LeagueRankings rankings() {
        return sources.open().rankings();
    }

    private void feedsReady() {
        SleeperStubs.healthyInSeason(sleeper);
        NflverseStubs.healthy(nflverse);
        NflverseStubs.pressureSeason(nflverse);
        OutboundStubs.telegramOk(telegram);
    }

    @Test
    void tightEndsRankByTargetsPerGameHoweverSmallTheirSample() {
        feedsReady();
        NflverseStubs.stubCsv(nflverse, NflverseStubs.STATS_2026_PATH,
                "nflverse/stats-player-week-rankings.csv");
        feeds.updateIfDue();

        Ranking tightEnds = rankings().players(PlayerStat.TARGETS_PER_GAME, Position.TE).orElseThrow();

        assertThat(tightEnds.ranked()).extracting(Ranking.Placement::id)
                .containsExactly(PHI_TE, SF_TE, BUF_TE, ATL_TE, GB_TE);
        assertThat(tightEnds.placement(PHI_TE).orElseThrow().value()).isEqualTo(11.0);
        assertThat(tightEnds.placement(SF_TE).orElseThrow().rank()).isEqualTo(2);
        assertThat(tightEnds.placement(ATL_TE).orElseThrow().rank()).isEqualTo(3);
        assertThat(tightEnds.placement(BUF_TE).orElseThrow().rank()).isEqualTo(3);
        assertThat(tightEnds.placement(BUF_TE).orElseThrow().rankFromBottom()).isEqualTo(2);
        assertThat(tightEnds.placement(GB_TE).orElseThrow().sample()).isEqualTo(9);
        assertThat(tightEnds.placement(GB_TE).orElseThrow().rank()).isEqualTo(5);
        // Gadsden is in the healthy play-by-play, but his team's weeks are
        // not in these weekly stats, so his targets are unknown.
        assertThat(tightEnds.unranked()).contains(new Ranking.Unranked(
                GADSDEN, null, Ranking.Reason.MISSING, 0));
        assertThat(tightEnds.population().describe())
                .isEqualTo("of 5 tight ends");
        assertThat(tightEnds.weeks()).isEqualTo(new Ranking.Weeks("2026", false, 1, 3));
        assertThat(tightEnds.stat().direction()).isEqualTo(Direction.HIGHER_IS_BETTER);
    }

    @Test
    void aStatNoStoredSourceHoldsHasNoRanking() {
        feedsReady();
        NflverseStubs.stubCsv(nflverse, NflverseStubs.STATS_2026_PATH,
                "nflverse/stats-player-week-rankings.csv");
        feeds.updateIfDue();

        assertThat(rankings().players(PlayerStat.SNAP_SHARE, Position.TE)).isEmpty();
    }

    @Test
    void defensesRankAgainstAPositionInLeagueScoringWithTheToughestFirst() {
        feedsReady();
        feeds.updateIfDue();
        defenseBuilder.build();

        Ranking backs = rankings().defenses(DefenseStat.POINTS_ALLOWED, Position.RB).orElseThrow();

        Ranking.Placement detroit = backs.placement("DET").orElseThrow();
        assertThat(detroit.value()).isCloseTo(32.5, within(0.001));
        assertThat(detroit.rank()).isEqualTo(4);
        assertThat(detroit.rankFromBottom()).isEqualTo(1);
        Ranking.Placement miami = backs.placement("MIA").orElseThrow();
        assertThat(miami.value()).isCloseTo(13.0, within(0.001));
        assertThat(miami.rank()).isEqualTo(1);
        assertThat(backs.population().describe()).isEqualTo("of 4 defenses");
        assertThat(backs.weeks()).isEqualTo(new Ranking.Weeks("2026", false, 1, 1));
        assertThat(backs.stat().direction()).isEqualTo(Direction.HIGHER_IS_BETTER);
    }

    @Test
    void noDefenseIsRankedAgainstAPositionBeforeTheTableIsBuilt() {
        feedsReady();
        feeds.updateIfDue();

        assertThat(rankings().defenses(DefenseStat.POINTS_ALLOWED, Position.RB)).isEmpty();
    }

    @Test
    void aDefenseStatIsRankedOnlyAgainstThePositionsItDescribes() {
        assertThatThrownBy(() -> rankings().defenses(DefenseStat.PRESSURE_RATE, Position.TE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pressure rate");
    }

    @Test
    void aDefenseWithNoMeasuredGameIsUnrankedOnPressureRate() {
        feedsReady();
        feeds.updateIfDue();

        Ranking pressure = rankings().defenses(DefenseStat.PRESSURE_RATE, Position.QB).orElseThrow();

        assertThat(pressure.ranked().getFirst().id()).isEqualTo("MIN");
        assertThat(pressure.placement("SF").orElseThrow().value())
                .isCloseTo(29.0 / 111.0, within(1e-9));
        assertThat(pressure.placement("CLE")).isEmpty();
        assertThat(pressure.unranked()).extracting(Ranking.Unranked::id, Ranking.Unranked::reason)
                .contains(tuple("CLE", Ranking.Reason.MISSING));
        assertThat(pressure.weeks()).isEqualTo(new Ranking.Weeks("2026", false, 1, 4));
        assertThat(pressure.stat().direction()).isEqualTo(Direction.HIGHER_IS_WORSE);
    }

    @Test
    void defensesRankByYardsBeforeContactAllowedPerCarry() {
        feedsReady();
        feeds.updateIfDue();

        Ranking contact = rankings().defenses(DefenseStat.YARDS_BEFORE_CONTACT_ALLOWED, Position.RB)
                .orElseThrow();

        Ranking.Placement carolina = contact.placement("CAR").orElseThrow();
        assertThat(carolina.rankFromBottom()).as("allows the most, the easiest").isEqualTo(1);
        assertThat(carolina.value()).isCloseTo(60.0 / 22, within(1e-9));
        Ranking.Placement jets = contact.placement("NYJ").orElseThrow();
        assertThat(jets.rank()).as("allows the least, the toughest").isEqualTo(1);
        assertThat(contact.population().size()).isEqualTo(17);
        assertThat(contact.weeks()).isEqualTo(new Ranking.Weeks("2026", false, 1, 4));
    }

    @Test
    void runningBacksRankByYardsBeforeContactPerCarryFromTheirFirstCarry() {
        feedsReady();
        feeds.updateIfDue();

        Ranking backs = rankings().players(PlayerStat.YARDS_BEFORE_CONTACT_PER_CARRY, Position.RB)
                .orElseThrow();

        assertThat(backs.ranked()).extracting(Ranking.Placement::name).containsExactly(
                "Saquon Barkley", "Bijan Robinson", "James Cook", "Christian McCaffrey",
                "Isaac Guerendo", "Josh Jacobs");
        assertThat(backs.placement(BARKLEY).orElseThrow().value())
                .isCloseTo(58.0 / 22, within(1e-9));
        assertThat(backs.placement(JACOBS).orElseThrow().sample()).isEqualTo(51);
        assertThat(backs.placement(GUERENDO).orElseThrow().sample()).isEqualTo(6);
        assertThat(backs.unranked()).isEmpty();
        assertThat(backs.population().describe()).isEqualTo("of 6 running backs");
        assertThat(backs.unmatched()).as("carriers the register cannot place")
                .hasSize(12)
                .contains("Running Back ARI");
        assertThat(backs.weeks()).isEqualTo(new Ranking.Weeks("2026", false, 1, 3));
    }

    @Test
    void quarterbacksRankByPressuredRateFromTheirFirstDropback() {
        feedsReady();
        feeds.updateIfDue();

        Ranking passers = rankings().players(PlayerStat.PRESSURED_RATE, Position.QB).orElseThrow();

        assertThat(passers.ranked()).extracting(Ranking.Placement::name)
                .containsExactly("Jordan Love", "Matthew Stafford", "Josh Allen", "Cooper Rush",
                        "Brock Purdy");
        assertThat(passers.placement(LOVE).orElseThrow().value())
                .isCloseTo(37.0 / 132, within(1e-9));
        assertThat(passers.placement(PURDY).orElseThrow().sample()).isEqualTo(89);
        assertThat(passers.placement(RUSH).orElseThrow().sample()).isEqualTo(46);
        assertThat(passers.unranked()).extracting(Ranking.Unranked::id)
                .doesNotContain("JohnCa02");
        assertThat(passers.stat().direction()).isEqualTo(Direction.HIGHER_IS_WORSE);
        assertThat(passers.unmatched()).contains("Jared Goff").doesNotContain("Brock Purdy");
    }
}
