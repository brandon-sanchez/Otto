package otto;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import otto.check.CheckRunner;
import otto.harness.Fixtures;
import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.nflverse.NflverseFeedService;
import otto.storage.DocumentBackend;
import otto.telegram.TelegramWebhook;
import otto.telegram.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A whole-league buy-low scan on a day FantasyCalc's list carries no
 * 30-day trends, so every player's value change is measured from the
 * lists Otto kept, one stored document a day.
 */
class MarketTrendReadsScenarioTest extends WireSeamTest {

    private static final String DAY_SLOT = "market-values-.*-day-\\d+";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private TelegramWebhook webhook;

    @MockitoSpyBean
    private DocumentBackend backend;

    private void theThirdWeekIsOnDiskWithoutTrends() {
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
                .withBody(Fixtures.read("fantasycalc/values-buy-sell.json")
                        .replaceAll("\"trend30Day\": -?\\d+,", ""))));
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "An alert.");
        checkRunner.runCheck();
        feeds.updateIfDue();
        llm.resetAll();
    }

    @Test
    void aLeagueScanLoadsEachKeptDayOfValuesAtMostOnce() {
        theThirdWeekIsOnDiskWithoutTrends();
        clock.advance(Duration.ofDays(1));
        OutboundStubs.llmCallsToolThenPhrases(llm, "find_buy_low_sell_high", "{}",
                "Here are your buy-lows and sell-highs.");
        Mockito.clearInvocations(backend);

        assertThat(webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage(
                "who should I buy low, and is anyone on my team a sell-high?")))
                .isEqualTo(WebhookResult.OK);

        Map<String, Long> loads = Mockito.mockingDetails(backend).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("loadVersioned"))
                .map(call -> (String) call.getArgument(0))
                .filter(name -> name.matches(DAY_SLOT))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        assertThat(loads).hasSizeGreaterThan(1);
        assertThat(loads.values()).allMatch(count -> count == 1);
    }
}
