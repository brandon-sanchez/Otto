package otto;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.ask.UserWeekLoader;
import otto.check.CheckRunner;
import otto.harness.KickerAndDefenseLeague;
import otto.harness.OutboundStubs;
import otto.harness.WireSeamTest;
import otto.league.LeagueShape;
import otto.league.LeagueShapes;
import otto.league.LeagueWeek;
import otto.league.TeamDepth;
import otto.lineup.PlayerPositions;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;
import otto.trade.TradeAsset;
import otto.trade.TradeEvaluator;
import otto.trade.TradePiece;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * A twelve-team league with one K and one DEF slot reads both positions'
 * Replacement Level at the twelfth player, the same in a team's depth
 * and in a trade's price.
 */
class KickerAndDefenseReplacementScenarioTest extends WireSeamTest {

    /** What the user's own kicker projects each week. */
    private static final double OWN_KICKER = 9.0;
    private static final double TWELFTH_KICKER = 2.0;
    private static final double TWELFTH_DEFENSE = 4.0;

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private UserWeekLoader loader;

    @Autowired
    private LeagueShapes shapes;

    @Autowired
    private TradeEvaluator trades;

    private LeagueWeek league;

    @BeforeEach
    void aKickerAndDefenseLeagueAfterOneCheck() {
        KickerAndDefenseLeague.stubDeepTable(sleeper, KickerAndDefenseLeague.KICKER,
                KickerAndDefenseLeague.DEFENSE);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "A lineup alert.");
        checkRunner.runCheck();
        league = ((SourceResult.Ok<LeagueWeek>) loader.leagueWeek()).value();
    }

    @Test
    void kickersAndDefensesRankAtTeamsTimesTheirSlots() {
        LeagueShape shape = shapes.shape(league);

        assertThat(shape.replacementRank(PlayerPositions.KICKER)).isEqualTo(OptionalInt.of(12));
        assertThat(shape.replacementRank(PlayerPositions.DEFENSE)).isEqualTo(OptionalInt.of(12));
    }

    @Test
    void depthReadsKickersAndDefensesAtTheTwelfthPlayer() {
        RosterSnapshot user = league.userRoster().orElseThrow();

        TeamDepth depth = TeamDepth.of(league, shapes.shape(league), shapes.ranking(league),
                user);

        assertThat(depth.at(PlayerPositions.KICKER).orElseThrow().replacement())
                .isEqualTo(String.valueOf(TWELFTH_KICKER));
        assertThat(depth.at(PlayerPositions.DEFENSE).orElseThrow().replacement())
                .isEqualTo(String.valueOf(TWELFTH_DEFENSE));
    }

    @Test
    void aTradeReadsTheSameReplacementLevelAsDepth() {
        TradeEvaluator.Basis basis =
                ((SourceResult.Ok<TradeEvaluator.Basis>) trades.basis(league)).value();
        int weeks = basis.season().weekCount();

        assertThat(basis.replacement().get(PlayerPositions.KICKER))
                .isEqualTo(TWELFTH_KICKER * weeks);
        assertThat(basis.replacement().get(PlayerPositions.DEFENSE))
                .isEqualTo(TWELFTH_DEFENSE * weeks);
    }

    @Test
    void aTradeStartsTheRostersOwnKickerOverAReplacementOne() {
        TradeEvaluator.Basis basis =
                ((SourceResult.Ok<TradeEvaluator.Basis>) trades.basis(league)).value();
        Map<String, String> kickerUnplaced = new HashMap<>(basis.positions());
        kickerUnplaced.remove(KickerAndDefenseLeague.KICKER);
        TradeEvaluator.Basis withoutTheKicker = new TradeEvaluator.Basis(basis.season(),
                basis.shape(), basis.replacement(), basis.injured(), kickerUnplaced);

        double withHim = startersNow(basis);
        double withAFiller = startersNow(withoutTheKicker);

        assertThat(withHim - withAFiller).isCloseTo(
                (OWN_KICKER - TWELFTH_KICKER) * basis.season().weekCount(), within(0.1));
    }

    private double startersNow(TradeEvaluator.Basis basis) {
        RosterSnapshot user = league.userRoster().orElseThrow();
        RosterSnapshot partner = league.rosters().stream()
                .filter(roster -> !roster.userRoster()).findFirst().orElseThrow();
        List<TradePiece> gets = List.of(new TradePiece(new TradeAsset.DraftPick("2027 1st"),
                null));
        List<TradePiece> gives = List.of(new TradePiece(new TradeAsset.DraftPick("2027 2nd"),
                null));
        return Double.parseDouble(
                trades.price(league, basis, user, partner, gets, gives).yours().startersNow());
    }
}
