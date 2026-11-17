package otto;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import otto.check.CheckRunner;
import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.StoredRecords;
import otto.harness.WireSeamTest;
import otto.nflverse.NflverseFeedService;
import otto.records.DecisionRecord;
import otto.records.DecisionRecords;
import otto.records.Outcome;
import otto.records.RunKind;
import otto.telegram.TelegramWebhook;
import otto.telegram.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

class AskToolDeadlineScenarioTest extends WireSeamTest {

    private static final String USER_ID = "777001";
    private static final String LEAGUE_ID = "1389748403805650944";

    private static final Duration ASK_DEADLINE = Duration.ofSeconds(4);

    private static final String SORRY =
            "I can't reach my model to explain Josh Jacobs' ceiling right now.";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private TelegramWebhook webhook;

    @Autowired
    private DecisionRecords decisionRecords;

    @Autowired
    private OttoProperties properties;

    @DynamicPropertySource
    static void shortAskDeadline(DynamicPropertyRegistry registry) {
        registry.add("otto.models.budgets.ask.deadline", ASK_DEADLINE::toString);
        registry.add("otto.models.budgets.ask.reserve", () -> "PT1S");
        registry.add("spring.ai.openai.max-retries", () -> "0");
    }

    @Test
    void aToolWhoseModelHangsGivesUpInTimeForTheChatModelToSaySo() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH,
                "sleeper/projections-edge.json", "projections-v1");
        NflverseStubs.healthy(nflverse);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "An alert about the bench edge.");
        checkRunner.runCheck();
        feeds.updateIfDue();
        llm.resetAll();
        telegram.resetRequests();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_player_ceiling",
                "{\"player\":\"Josh Jacobs\"}", SORRY);
        llm.stubFor(post(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .atPriority(1)
                .withRequestBody(containing("legalMoves"))
                .willReturn(aResponse().withFixedDelay(30_000)
                        .withHeader("Content-Type", "application/json")
                        .withBody(OutboundStubs.contentBody("{}"))));

        long started = System.nanoTime();
        assertThat(webhook.handle(WEBHOOK_SECRET,
                OutboundStubs.textMessage("what's Josh Jacobs' ceiling?")))
                .isEqualTo(WebhookResult.OK);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).isLessThan(ASK_DEADLINE.plusSeconds(1));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(SORRY))));
        List<DecisionRecord> records = StoredRecords.of(decisionRecords,
                properties.storageDir(), USER_ID, LEAGUE_ID, RunKind.PLAYER_CEILING);
        assertThat(records).singleElement().satisfies(record ->
                assertThat(record.outcome()).isInstanceOf(Outcome.Withheld.class));
    }
}
