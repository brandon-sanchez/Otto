package otto;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import otto.check.CheckRunner;
import otto.harness.NflverseStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.nflverse.NflverseFeedService;
import otto.webhook.TelegramWebhook;
import otto.webhook.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A chat tool that asks a model of its own, with OpenAI, Claude and
 * Gemini all configured and the time budgets application.yml ships. The
 * tool's run fits inside the chat reply's time, which is shorter than
 * one advice reserve.
 */
class AskToolFallbackDeadlineScenarioTest extends WireSeamTest {

    private static final String CHECKED = "Josh Jacobs usually lands between 10 and 21 "
            + "points, a bad week drops to about 7, and a big week reaches 28 or more. He has "
            + "about a 39% chance of an RB12 week, which is 17.4 points in your league. As "
            + "history, he cleared that line in 1 of his 1 games this season.";

    private static final WireMockServer claude = newStubServer();
    private static final WireMockServer gemini = newStubServer();

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private NflverseFeedService feeds;

    @Autowired
    private TelegramWebhook webhook;

    private static WireMockServer newStubServer() {
        WireMockServer server = new WireMockServer(
                WireMockConfiguration.options().dynamicPort().gzipDisabled(true));
        server.start();
        return server;
    }

    @DynamicPropertySource
    static void fallbackProviders(DynamicPropertyRegistry registry) {
        registry.add("otto.models.anthropic.base-url", claude::baseUrl);
        registry.add("otto.models.anthropic.api-key", () -> "test-anthropic-key");
        registry.add("otto.models.anthropic.max-retries", () -> "0");
        registry.add("otto.models.gemini.base-url", gemini::baseUrl);
        registry.add("otto.models.gemini.api-key", () -> "test-gemini-key");
        registry.add("otto.models.gemini.max-retries", () -> "0");
        registry.add("spring.ai.openai.max-retries", () -> "0");
    }

    @BeforeEach
    void fallbacksAreDown() {
        claude.resetAll();
        gemini.resetAll();
        claude.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(503)));
        gemini.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(503)));
    }

    private void aWeekOfDataOnDisk() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH,
                "sleeper/projections-edge.json", "projections-v1");
        NflverseStubs.healthy(nflverse);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "An alert about the bench edge.");
        checkRunner.runCheck();
        feeds.updateIfDue();
        llm.resetAll();
        claude.resetRequests();
        gemini.resetRequests();
        telegram.resetRequests();
        OutboundStubs.telegramOk(telegram);
    }

    @Test
    void aToolsModelRunAsksThePrimaryProviderFirst() {
        aWeekOfDataOnDisk();
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_player_ceiling",
                "{\"player\":\"Josh Jacobs\"}", "Here is what Jacobs can do.");
        OutboundStubs.llmAdvises(llm, """
                {"message": %s, "move": null, "players": ["5850"], "claims": []}
                """.formatted(OutboundStubs.jsonString(CHECKED)));

        assertThat(webhook.handle(WEBHOOK_SECRET,
                OutboundStubs.textMessage("what's Josh Jacobs' ceiling?")))
                .isEqualTo(WebhookResult.OK);

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("legalMoves")));
        claude.verify(0, anyRequestedFor(anyUrl()));
        gemini.verify(0, anyRequestedFor(anyUrl()));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing(OutboundStubs.jsonString(CHECKED))));
    }
}
