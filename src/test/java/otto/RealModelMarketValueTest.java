package otto;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.ask.SettingsTools;
import otto.harness.Fixtures;
import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.RealModel.Rehearsal;
import otto.harness.RealModelScenario;
import otto.harness.SleeperStubs;
import otto.nflverse.NflverseFeedService;
import otto.records.DecisionRecord;
import otto.records.RunKind;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Market values against the real model, from FantasyCalc's recorded
 * lists, in a Superflex league, a one-quarterback half-PPR league, and a
 * keeper league that prices keepers from its drafts, as
 * {@code MarketValueScenarioTest} and {@code KeeperCostScenarioTest}
 * describe.
 */
class RealModelMarketValueTest extends RealModelScenario {

    private static final String NACUA = "9493";
    private static final String BIJAN = "9509";

    private static final String DYNASTY_PROCESS_PATH =
            "/dynastyprocess/data/master/files/values.csv";
    private static final String PREVIOUS_LEAGUE_PATH = "/v1/league/1250031856205725696";
    private static final String DRAFT_PICKS_PATH = "/v1/draft/1389748403805650945/picks";
    private static final String PREVIOUS_DRAFT_PICKS_PATH = "/v1/draft/1250031856205725697/picks";

    private static final Rehearsal NACUA_VALUE = new Rehearsal("get_market_value",
            "{\"subject\":\"Puka Nacua\"}", checkedAnswer("Puka Nacua's value is down this "
                    + "month, so this is a cheap time to ask about him.", NACUA));

    @Autowired
    private SettingsTools tools;

    @Autowired
    private NflverseFeedService feeds;

    private static MappingBuilder valuesFor(boolean dynasty, int quarterbacks, int teams,
            String ppr) {
        return get(urlPathEqualTo("/values/current"))
                .withQueryParam("isDynasty", equalTo(String.valueOf(dynasty)))
                .withQueryParam("numQbs", equalTo(String.valueOf(quarterbacks)))
                .withQueryParam("numTeams", equalTo(String.valueOf(teams)))
                .withQueryParam("ppr", equalTo(ppr));
    }

    private static void fantasyCalcAnswers(MappingBuilder request, String fixture) {
        fantasyCalc.stubFor(request.willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(Fixtures.read(fixture))));
    }

    private void league(String fixture) {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH, fixture, "league-market");
        OutboundStubs.telegramOk(telegram);
    }

    @Test
    void aSuperflexLeagueReadsTheSuperflexList() {
        league("sleeper/league-in-season.json");
        fantasyCalcAnswers(valuesFor(false, 2, 12, "1"), "fantasycalc/values-redraft.json");

        ask("What's Puka Nacua worth in my league?", NACUA_VALUE);

        DecisionRecord answer = sentAfterPassingItsCheck(RunKind.MARKET_VALUE);
        assertThat(evidence(answer).get("market-value-" + NACUA))
                .containsEntry("league", "12-team Superflex PPR redraft");
        assertThat(namedPlayers(answer)).contains(NACUA);
    }

    @Test
    void aOneQuarterbackHalfPprLeagueReadsItsOwnList() {
        league("sleeper/league-market-1qb-half-ppr.json");
        fantasyCalcAnswers(valuesFor(false, 1, 10, "0.5"), "fantasycalc/values-redraft.json");

        ask("What's Puka Nacua worth in my league?", NACUA_VALUE);

        DecisionRecord answer = sentAfterPassingItsCheck(RunKind.MARKET_VALUE);
        assertThat(evidence(answer).get("market-value-" + NACUA))
                .containsEntry("league", "10-team 1QB half-PPR redraft");
        assertThat(namedPlayers(answer)).contains(NACUA);
    }

    /**
     * Bijan Robinson was kept in round 1 of the 2026 draft, and the owner
     * has said a keeper costs the round he went in the season before.
     */
    @Test
    void aLikelyKeepersValueCountsWhatKeepingHimCosts() {
        league("sleeper/keeper/league-snake-2026.json");
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH, "sleeper/rosters.json",
                "rosters-v1");
        checkRunsQuietly();
        NflverseStubs.healthy(nflverse);
        feeds.updateIfDue();
        nflverse.stubFor(get(urlEqualTo(DYNASTY_PROCESS_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "text/csv")
                .withBody(Fixtures.read("dynastyprocess/values.csv"))));
        fantasyCalcAnswers(false, "fantasycalc/values-redraft.json");
        fantasyCalcAnswers(true, "fantasycalc/values-keeper-dynasty.json");
        drafts(SleeperStubs.LEAGUE_PATH, "drafts-snake-2026.json", DRAFT_PICKS_PATH,
                "picks-snake-2026.json");
        SleeperStubs.stubJson(sleeper, PREVIOUS_LEAGUE_PATH,
                "sleeper/keeper/league-snake-2025.json", "league-2025");
        drafts(PREVIOUS_LEAGUE_PATH, "drafts-snake-2025.json", PREVIOUS_DRAFT_PICKS_PATH,
                "picks-snake-2025.json");
        tools.manageKeeperRule("set", "same", null, null, null, null);

        ask("What's Bijan Robinson worth in my keeper league?", new Rehearsal(
                "get_market_value", "{\"subject\":\"Bijan Robinson\"}", checkedAnswer(
                        "Bijan Robinson is a likely keeper, and keeping him costs a round 1 "
                                + "pick next season.", BIJAN)));

        DecisionRecord answer = sentAfterPassingItsCheck(RunKind.MARKET_VALUE);
        assertThat(evidence(answer).get("market-keeper-" + BIJAN))
                .containsEntry("likelyKeeper", "yes")
                .containsEntry("keeperCost", "5166");
        assertThat(namedPlayers(answer)).contains(BIJAN);
    }

    private void drafts(String leaguePath, String draftsFixture, String picksPath,
            String picksFixture) {
        SleeperStubs.stubJson(sleeper, leaguePath + "/drafts", "sleeper/keeper/" + draftsFixture,
                draftsFixture);
        SleeperStubs.stubJson(sleeper, picksPath, "sleeper/keeper/" + picksFixture,
                picksFixture);
    }
}
