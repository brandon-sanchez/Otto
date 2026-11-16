package otto;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.ask.AskTools;
import otto.ask.ToolAnswer;
import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.ask.LeagueRankLookup;
import otto.nflverse.DefenseVersusPositionBuilder;
import otto.nflverse.NflverseFeedService;
import otto.telegram.TelegramWebhook;
import otto.telegram.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

class LeagueRankToolScenarioTest extends WireSeamTest {

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private DefenseVersusPositionBuilder defenseBuilder;

    @Autowired
    private AskTools tools;

    @Autowired
    private TelegramWebhook webhook;

    private void feedsReady() {
        SleeperStubs.healthyInSeason(sleeper);
        NflverseStubs.healthy(nflverse);
        NflverseStubs.pressureSeason(nflverse);
        OutboundStubs.telegramOk(telegram);
        feeds.updateIfDue();
        defenseBuilder.build();
    }

    @Test
    void aDefensesRankAgainstAPositionComesWithItsToneAndTheTopOfTheTable() {
        feedsReady();

        ToolAnswer<LeagueRankLookup.Answer> answer =
                tools.getLeagueRank("points_allowed", "RB", "Lions");

        assertThat(answer.unavailable()).isNull();
        LeagueRankLookup.Answer facts = answer.facts();
        assertThat(facts.stat()).isEqualTo("fantasy points allowed per game to running backs");
        assertThat(facts.population()).isEqualTo("of 4 defenses");
        assertThat(facts.rankMeaning()).startsWith("1st is the toughest defense");
        assertThat(facts.asked().name()).isEqualTo("DET");
        assertThat(facts.asked().rank()).isEqualTo("4th");
        assertThat(facts.asked().rankFromBottom()).isEqualTo("1st from the bottom");
        assertThat(facts.asked().matchup()).isEqualTo("green: an easy matchup for running backs");
        assertThat(facts.top()).first().satisfies(top -> {
            assertThat(top.name()).isEqualTo("MIA");
            assertThat(top.rank()).isEqualTo("1st");
        });
    }

    @Test
    void aPassRushRanksTheMostPressureFirst() {
        feedsReady();

        LeagueRankLookup.Answer facts = tools.getLeagueRank("pressure rate", "QB", null).facts();

        assertThat(facts.top()).first().satisfies(top -> {
            assertThat(top.name()).isEqualTo("MIN");
            assertThat(top.matchup()).startsWith("red");
        });
        assertThat(facts.asked()).isNull();
    }

    @Test
    void aPlayerIsFoundByNameAndRankedOnHowEverFewDropbacks() {
        feedsReady();

        LeagueRankLookup.Answer love = tools.getLeagueRank("pressured_rate", "QB", "jordan love")
                .facts();
        LeagueRankLookup.Answer rush = tools.getLeagueRank("pressured_rate", "QB", "Cooper Rush")
                .facts();

        assertThat(love.asked().rank()).isEqualTo("1st");
        assertThat(love.asked().matchup()).startsWith("red");
        assertThat(rush.asked().rank()).isEqualTo("4th");
        assertThat(rush.asked().quoted()).isEqualTo("4th in pressured rate, on 46 dropbacks");
    }

    @Test
    void aPlayerIsFoundByHisSurnameOrHisNameWrittenLoosely() {
        feedsReady();

        assertThat(tools.getLeagueRank("pressured_rate", "QB", "Love").facts().asked().rank())
                .isEqualTo("1st");
        assertThat(tools.getLeagueRank("pressured_rate", "QB", "JORDAN  LOVE.").facts().asked())
                .isNotNull();
        assertThat(tools.getLeagueRank("pressured_rate", "QB", "rush").facts().asked().name())
                .isEqualTo("Cooper Rush");
    }

    @Test
    void anUnknownStatOrAPositionTheStatDoesNotCoverIsUnavailable() {
        feedsReady();

        assertThat(tools.getLeagueRank("vibes", "QB", null).unavailable())
                .contains("vibes").contains("target_share");
        assertThat(tools.getLeagueRank("blitz_rate", "TE", null).unavailable())
                .contains("QB");
    }

    @Test
    void anAskForADefensesRankReachesTheModelAsTheComputedRank() {
        feedsReady();
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_league_rank",
                "{\"stat\":\"points_allowed\",\"position\":\"RB\",\"who\":\"DET\"}",
                "Detroit is the easiest running-back matchup.");

        assertThat(webhook.handle(WEBHOOK_SECRET,
                OutboundStubs.textMessage("How tough is the Lions run defense for RBs?")))
                .isEqualTo(WebhookResult.OK);

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("1st from the bottom"))
                .withRequestBody(containing("green: an easy matchup for running backs")));
    }
}
