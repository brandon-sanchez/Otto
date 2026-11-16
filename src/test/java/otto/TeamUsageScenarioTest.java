package otto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import otto.ask.TeamUsage;
import otto.ask.ToolAnswer;
import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.nflverse.NflverseFeedService;
import otto.telegram.TelegramWebhook;
import otto.telegram.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

class TeamUsageScenarioTest extends WireSeamTest {

    /** Telegram rejects a message longer than this many characters. */
    private static final int TELEGRAM_MESSAGE_LIMIT = 4096;

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private TeamUsage usage;

    @Autowired
    private TelegramWebhook webhook;

    @BeforeEach
    void chargersFinishWeeksThreeToFiveWithStatsForFourAndFiveAndSnapsForFour() {
        SleeperStubs.healthyInSeason(sleeper);
        NflverseStubs.chargersUsage(nflverse);
        NflverseStubs.stubCsv(nflverse, NflverseStubs.SCHEDULE_PATH,
                "nflverse/games-chargers-usage.csv");
        OutboundStubs.telegramOk(telegram);

        feeds.updateIfDue();
    }

    private TeamUsage.Report report(String team, String group) {
        ToolAnswer<TeamUsage.Report> answer = usage.forTeam(team, group);
        assertThat(answer.unavailable()).isNull();
        return answer.facts();
    }

    @Test
    void eachBackHasHisSnapsTargetsAndCarriesWithTheirSharesWeekByWeek() {
        TeamUsage.Report backs = report("LAC", "RB");

        assertThat(backs.team()).isEqualTo("LAC");
        assertThat(backs.group()).isEqualTo("RB");
        assertThat(backs.weeks()).containsExactly(3, 4, 5);
        assertThat(backs.table()).contains("""
                Week 4: RB 35% of targets (11 of 31), 75% of carries (24 of 32)
                - Keaton Mitchell: 26 snaps (37%), 6 targets (19%), 10 carries (31%)
                - Omarion Hampton: 25 snaps (35%), 2 targets (6%), 9 carries (28%)
                - Kimani Vidal: 25 snaps (35%), 3 targets (10%), 5 carries (16%)
                """);
    }

    @Test
    void aMissingSourceIsShownAsMissingNeverAsZero() {
        TeamUsage.Report backs = report("LAC", "RB");

        assertThat(backs.missingWeeks()).containsExactly(3);
        assertThat(backs.table())
                .contains("Week 3: missing - no stored stats for this game\n")
                .contains("""
                        Week 5: RB 33% of targets (7 of 21), 82% of carries (28 of 34)
                        - Omarion Hampton: snaps missing, 1 target (5%), 14 carries (41%)
                        - Keaton Mitchell: snaps missing, 4 targets (19%), 6 carries (18%)
                        - Kimani Vidal: snaps missing, 2 targets (10%), 8 carries (24%)
                        """)
                .doesNotContain("Week 6");
    }

    @Test
    void withoutAPositionEachGroupShowsItsShareOfTheTeamsTargetsAndCarries() {
        TeamUsage.Report groups = report("Chargers", null);

        assertThat(groups.group()).isNull();
        assertThat(groups.table()).contains("""
                Week 4
                - QB: 3% of targets (1 of 31), 19% of carries (6 of 32)
                - RB: 35% of targets (11 of 31), 75% of carries (24 of 32)
                - WR: 35% of targets (11 of 31), 3% of carries (1 of 32)
                - TE: 26% of targets (8 of 31), 3% of carries (1 of 32)
                """);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Chargers", "LAC", "LA Chargers", "los angeles chargers", "the Chargers"})
    void theTeamResolvesFromTheFormsPeopleUse(String team) {
        assertThat(report(team, "running backs").team()).isEqualTo("LAC");
    }

    @Test
    void anAmbiguousOrUnknownTeamIsRefusedRatherThanGuessed() {
        assertThat(usage.forTeam("LA", "RB").unavailable()).contains("LA");
        assertThat(usage.forTeam("Grizzlies", "RB").unavailable()).contains("Grizzlies");
    }

    @Test
    void anAskForATeamsBackfieldSendsTheComputedSplitAsItIsWithinTelegramsLimit() {
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_team_usage",
                "{\"team\":\"Chargers\",\"position\":\"RB\"}",
                "Keaton Mitchell played 90% of the snaps.");

        assertThat(webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage("Chargers RB usage")))
                .isEqualTo(WebhookResult.OK);

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("Keaton Mitchell: 26 snaps (37%)")));
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("90%")));
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH)));
        for (String group : new String[] {null, "QB", "RB", "WR", "TE"}) {
            assertThat(report("LAC", group).table()).hasSizeLessThan(TELEGRAM_MESSAGE_LIMIT / 2);
        }
    }

    @Test
    void aRunnerOnlyThePlayByPlayKnowsLeavesTheReportStanding() {
        TeamUsage.Report backs = report("LAC", "RB");

        assertThat(backs.table()).contains("- Keaton Mitchell").doesNotContain("Z.Unlisted");
        assertThat(report("LAC", null).table()).contains("- RB:");
    }
}
