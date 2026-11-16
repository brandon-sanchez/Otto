package otto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.harness.Fixtures;
import otto.harness.NflverseStubs;
import otto.harness.RealModel.Rehearsal;
import otto.harness.RealModelScenario;
import otto.harness.SleeperStubs;
import otto.nflverse.NflverseFeedService;
import otto.records.DecisionRecord;
import otto.records.RunKind;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Buy-low and sell-high answers against the real model, three weeks into
 * the 2026 season. The usage is real: ffopportunity's 2026 weeks 1 to 3,
 * nothing after them, beside its whole 2025 season, which still carries
 * most of each player's baseline this early. The league is a 12-team
 * Superflex PPR redraft league with a tight-end catch premium, and
 * {@code BuyLowSellHighScenarioTest} describes each player's numbers.
 */
class RealModelBuyLowSellHighTest extends RealModelScenario {

    private static final String TOOL = "find_buy_low_sell_high";

    private static final String PICKENS = "8137";
    private static final String JAMESON_WILLIAMS = "8148";
    private static final String BARKLEY = "4866";
    private static final String MCBRIDE = "8130";
    private static final String SMITH_NJIGBA = "9488";

    @Autowired
    private NflverseFeedService feeds;

    @BeforeEach
    void theThirdWeekIsOnDisk() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-buy-sell.json", "players-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH, "sleeper/rosters-buy-sell.json",
                "rosters-v1");
        NflverseStubs.healthy(nflverse);
        NflverseStubs.stubCsv(nflverse, NflverseStubs.DEPTH_2026_PATH,
                "nflverse/depth-charts-2026.csv");
        NflverseStubs.stubCsv(nflverse, NflverseStubs.SCHEDULE_PATH,
                "nflverse/games-buy-sell.csv");
        NflverseStubs.stubCsv(nflverse, NflverseStubs.EXPECTED_POINTS_2026_PATH,
                "nflverse/ep-weekly-2026-buy-sell.csv");
        NflverseStubs.stubCsv(nflverse, NflverseStubs.EXPECTED_POINTS_2025_PATH,
                "nflverse/ep-weekly-2025-skill.csv");
        NflverseStubs.stubCsv(nflverse, NflverseStubs.PLAYER_IDS_PATH,
                "nflverse/db-playerids-buy-sell.csv");
        fantasyCalc.stubFor(get(urlPathEqualTo("/values/current")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(Fixtures.read("fantasycalc/values-buy-sell.json"))));
        checkRunsQuietly();
        feeds.updateIfDue();
    }

    @Test
    void aLeagueScanNamesTheBuyLowsAndTheUsersSellHigh() {
        ask("who should I buy low, and is anyone on my team a sell-high?", new Rehearsal(TOOL,
                "{}", checkedAnswer("George Pickens is a buy-low: he is scoring 4.6 points a "
                        + "game below what his usage is worth. On your team, Jaxon Smith-Njigba "
                        + "is a sell-high, so expect about 22.2 a game from here.", PICKENS,
                        SMITH_NJIGBA)));

        DecisionRecord answer = sentAfterPassingItsCheck(RunKind.BUY_LOW_SELL_HIGH);
        assertThat(answer.candidates())
                .extracting(candidate -> candidate.playerId() + " " + candidate.reason())
                .containsExactly(PICKENS + " BUY_LOW", JAMESON_WILLIAMS + " BUY_LOW",
                        MCBRIDE + " MARKET_BUY_LOW", SMITH_NJIGBA + " SELL_HIGH");
        assertThat(namedPlayers(answer)).contains(PICKENS, SMITH_NJIGBA).doesNotContain(BARKLEY);
    }

    @Test
    void aPlayerWhoseRoleShrankIsCalledARoleDecline() {
        ask("is Saquon Barkley a buy-low?", new Rehearsal(TOOL,
                "{\"player\":\"Saquon Barkley\"}", checkedAnswer("Saquon Barkley is a role "
                        + "decline, not a buy-low: his xFP a game is down 34% from last "
                        + "season.", BARKLEY)));

        DecisionRecord answer = sentAfterPassingItsCheck(RunKind.BUY_LOW_SELL_HIGH);
        assertThat(answer.candidates()).extracting(candidate -> candidate.reason())
                .containsExactly("ROLE_DECLINE");
        assertThat(namedPlayers(answer)).contains(BARKLEY);
    }

    @Test
    void askingWhetherToSellTheUsersHotPlayerJudgesHimASellHigh() {
        ask("should I sell Jaxon Smith-Njigba?", new Rehearsal(TOOL,
                "{\"player\":\"Jaxon Smith-Njigba\"}", checkedAnswer("Sell Jaxon Smith-Njigba "
                        + "high: he is scoring 12.5 points a game above what his usage is "
                        + "worth, so expect about 22.2 a game from here.", SMITH_NJIGBA)));

        DecisionRecord answer = sentAfterPassingItsCheck(RunKind.BUY_LOW_SELL_HIGH);
        assertThat(answer.candidates()).extracting(candidate -> candidate.reason())
                .containsExactly("SELL_HIGH");
        assertThat(namedPlayers(answer)).contains(SMITH_NJIGBA);
    }
}
