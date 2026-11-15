package otto;

import java.time.Duration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import otto.check.CheckRunner;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
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

/**
 * Model calls when OpenAI is down: the chain asks Claude, then Gemini,
 * and only then falls back to what code alone can say. Two more
 * stub servers play Anthropic and Google; none of the three is ever the
 * real API.
 */
class ModelFallbackScenarioTest extends WireSeamTest {

    private static final String ANTHROPIC_MESSAGES = "/v1/messages";
    private static final String GEMINI_GENERATE = ".*:generateContent";

    private static final String CLAUDE_MODEL = "claude-sonnet-5-5";
    private static final String GEMINI_MODEL = "gemini-3.8-flash";

    private static final String EDGE_MESSAGE = "Start Josh Jacobs over James Cook at RB. "
            + "Josh Jacobs projects 16.5 points in your scoring and James Cook projects 13.0, "
            + "an edge of 3.5.";

    private static final Duration ASK_DEADLINE = Duration.ofSeconds(4);

    private static final WireMockServer claude = newStubServer();
    private static final WireMockServer gemini = newStubServer();

    @Autowired
    private CheckRunner checkRunner;

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
        registry.add("otto.models.budgets.ask.deadline", () -> ASK_DEADLINE.toString());
        registry.add("otto.models.budgets.ask.reserve", () -> "PT1S");
    }

    @BeforeEach
    void resetFallbackServers() {
        claude.resetAll();
        gemini.resetAll();
    }

    private static void openAiIsDown() {
        llm.stubFor(post(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .willReturn(serverError()));
    }

    private static void claudeIsDown() {
        claude.stubFor(post(urlPathMatching(ANTHROPIC_MESSAGES)).willReturn(serverError()));
    }

    private static void geminiIsDown() {
        gemini.stubFor(post(urlPathMatching(GEMINI_GENERATE)).willReturn(serverError()));
    }

    private static ResponseDefinitionBuilder serverError() {
        return aResponse().withStatus(503)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"overloaded\"}}");
    }

    /** Claude's replies in order; the last one repeats. */
    private static void claudeAnswers(String... replies) {
        for (int index = 0; index < replies.length; index++) {
            String state = index == 0 ? Scenario.STARTED : "reply-" + index;
            String next = index == replies.length - 1 ? state : "reply-" + (index + 1);
            claude.stubFor(post(urlPathMatching(ANTHROPIC_MESSAGES))
                    .inScenario("claude")
                    .whenScenarioStateIs(state)
                    .willReturn(json(anthropicMessage(replies[index])))
                    .willSetStateTo(next));
        }
    }

    private static void geminiAnswers(String reply) {
        gemini.stubFor(post(urlPathMatching(GEMINI_GENERATE))
                .willReturn(json(geminiResponse(reply))));
    }

    private static ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }

    private static String anthropicMessage(String text) {
        return """
                {
                  "id": "msg_test",
                  "type": "message",
                  "role": "assistant",
                  "model": "%s",
                  "content": [{"type": "text", "text": %s}],
                  "stop_reason": "end_turn",
                  "stop_sequence": null,
                  "usage": {"input_tokens": 10, "output_tokens": 10}
                }
                """.formatted(CLAUDE_MODEL, OutboundStubs.jsonString(text));
    }

    private static String geminiResponse(String text) {
        return """
                {
                  "candidates": [{
                    "content": {"role": "model", "parts": [{"text": %s}]},
                    "finishReason": "STOP",
                    "index": 0
                  }],
                  "usageMetadata": {"promptTokenCount": 10, "candidatesTokenCount": 10,
                      "totalTokenCount": 20},
                  "modelVersion": "%s"
                }
                """.formatted(OutboundStubs.jsonString(text), GEMINI_MODEL);
    }

    private void checkWithAnEdge() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH,
                "sleeper/projections-edge.json", "projections-v1");
        OutboundStubs.telegramOk(telegram);
        checkRunner.runCheck();
    }

    private void edgeMessageWasSent() {
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(EDGE_MESSAGE))));
    }

    @Test
    void claudePhrasesTheAlertWhenOpenAiIsDown() {
        openAiIsDown();
        claudeAnswers(EDGE_MESSAGE);

        checkWithAnEdge();

        edgeMessageWasSent();
        claude.verify(1, postRequestedFor(urlEqualTo(ANTHROPIC_MESSAGES))
                .withRequestBody(containing("Josh Jacobs")));
    }

    @Test
    void geminiPhrasesTheAlertWhenOpenAiAndClaudeAreDown() {
        openAiIsDown();
        claudeIsDown();
        geminiAnswers(EDGE_MESSAGE);

        checkWithAnEdge();

        edgeMessageWasSent();
        gemini.verify(1, postRequestedFor(urlPathMatching(".*/models/" + GEMINI_MODEL
                + ":generateContent")).withRequestBody(containing("Josh Jacobs")));
    }

    @Test
    void theCodeFallbackIsUsedWhenAllThreeAreDown() {
        openAiIsDown();
        claudeIsDown();
        geminiIsDown();

        checkWithAnEdge();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text",
                        containing("Start Josh Jacobs over James Cook"))));
        claude.verify(postRequestedFor(urlEqualTo(ANTHROPIC_MESSAGES)));
        gemini.verify(postRequestedFor(urlPathMatching(GEMINI_GENERATE)));
    }

    @Test
    void anAskIsAnsweredByClaudeWhenOpenAiIsDown() {
        openAiIsDown();
        claudeAnswers("Your lineup looks set.");
        OutboundStubs.telegramOk(telegram);

        ask("Is my lineup ok?");

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo("Your lineup looks set."))));
        claude.verify(1, postRequestedFor(urlEqualTo(ANTHROPIC_MESSAGES))
                .withRequestBody(containing("Is my lineup ok?"))
                .withRequestBody(containing("\"tools\"")));
    }

    @Test
    void claudeAnswersInsideTheAskDeadlineWhenOpenAiHangs() {
        llm.stubFor(post(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .willReturn(json(OutboundStubs.contentBody("Too late."))
                        .withFixedDelay(30_000)));
        claudeAnswers("Your lineup looks set.");
        OutboundStubs.telegramOk(telegram);

        long started = System.nanoTime();
        ask("Is my lineup ok?");
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).isLessThan(ASK_DEADLINE.plusSeconds(1));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo("Your lineup looks set."))));
        claude.verify(1, postRequestedFor(urlEqualTo(ANTHROPIC_MESSAGES)));
    }

    @Test
    void aProviderThatKeepsFailingIsLeftAloneUntilItsCooldownEnds() {
        openAiIsDown();
        claudeAnswers("Your lineup looks set.");
        OutboundStubs.telegramOk(telegram);

        for (int question = 0; question < 5; question++) {
            ask("Is my lineup ok?");
        }

        llm.verify(3, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH)));
        claude.verify(5, postRequestedFor(urlEqualTo(ANTHROPIC_MESSAGES)));

        clock.advance(Duration.ofMinutes(5));
        ask("Is my lineup ok?");

        llm.verify(4, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH)));
    }

    private void ask(String text) {
        assertThat(webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage(text)))
                .isEqualTo(WebhookResult.OK);
    }
}
