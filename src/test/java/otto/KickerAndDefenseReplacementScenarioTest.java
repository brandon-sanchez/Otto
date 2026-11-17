package otto;

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
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;
import otto.trade.TradeEvaluator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A twelve-team league with one K and one DEF slot reads both positions'
 * Replacement Level at the twelfth player, the same in a team's depth
 * and in a trade's price.
 */
class KickerAndDefenseReplacementScenarioTest extends WireSeamTest {

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

        assertThat(shape.replacementRank("K")).isEqualTo(OptionalInt.of(12));
        assertThat(shape.replacementRank("DEF")).isEqualTo(OptionalInt.of(12));
    }

    @Test
    void depthReadsKickersAndDefensesAtTheTwelfthPlayer() {
        RosterSnapshot user = league.userRoster().orElseThrow();

        TeamDepth depth = TeamDepth.of(league, shapes.shape(league), shapes.ranking(league),
                user);

        assertThat(depth.at("K").orElseThrow().replacement()).isEqualTo("2.0");
        assertThat(depth.at("DEF").orElseThrow().replacement()).isEqualTo("4.0");
    }

    @Test
    void aTradeReadsTheSameReplacementLevelAsDepth() {
        TradeEvaluator.Basis basis =
                ((SourceResult.Ok<TradeEvaluator.Basis>) trades.basis(league)).value();
        int weeks = basis.season().weekCount();

        assertThat(basis.replacement().get("K")).isEqualTo(TWELFTH_KICKER * weeks);
        assertThat(basis.replacement().get("DEF")).isEqualTo(TWELFTH_DEFENSE * weeks);
    }
}
