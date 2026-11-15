package otto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

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
import otto.records.DecisionRecord;
import otto.records.DecisionRecords;
import otto.records.ModelExchange;
import otto.records.Outcome;
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
 * Model-written advice when OpenAI is down: the chain asks Claude, then
 * Gemini, and only then falls back to what code alone can say. Two more
 * stub servers play Anthropic and Google; none of the three is ever the
 * real API.
 */
class ModelFallbackScenarioTest extends WireSeamTest {

    private static final String USER_ID = "777001";
    private static final String LEAGUE_ID = "1389748403805650944";

    private static final String ANTHROPIC_MESSAGES = "/v1/messages";
    private static final String GEMINI_GENERATE = ".*:generateContent";

    private static final String CLAUDE_MODEL = "claude-sonnet-5-5";
    private static final String GEMINI_MODEL = "gemini-3.8-flash";

    private static final String EDGE_MESSAGE = "Start Josh Jacobs over James Cook at RB. "
            + "Josh Jacobs projects 16.5 points in your scoring and James Cook projects 13.0, "
            + "an edge of 3.5.";

    private static final String EDGE_ANSWER = edgeAnswer(EDGE_MESSAGE);

    private static final String WRONG_NUMBER = edgeAnswer(EDGE_MESSAGE.replace("16.5", "18.0"));

    private static final WireMockServer claude = newStubServer();
    private static final WireMockServer gemini = newStubServer();

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private DecisionRecords decisionRecords;

    @Autowired
    private OttoProperties properties;

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
    void resetFallbackServers() {
        claude.resetAll();
        gemini.resetAll();
    }

    private static String edgeAnswer(String message) {
        return """
                {
                  "message": %s,
                  "move": {"start": "5850", "bench": "8138", "slot": "RB"},
                  "players": ["5850", "8138"],
                  "claims": [
                    {"text": "Josh Jacobs projects 16.5 points", "evidence": ["projection-5850"]},
                    {"text": "James Cook projects 13.0 points", "evidence": ["projection-8138"]}
                  ],
                  "comparisons": [{"higher": "5850", "lower": "8138", "basis": "projection"}]
                }
                """.formatted(OutboundStubs.jsonString(message));
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
    void claudeAnswersWhenOpenAiIsDown() {
        openAiIsDown();
        claudeAnswers(EDGE_ANSWER);

        checkWithAnEdge();

        edgeMessageWasSent();
        claude.verify(1, postRequestedFor(urlEqualTo(ANTHROPIC_MESSAGES))
                .withRequestBody(containing("State facts only from"))
                .withRequestBody(containing("legalMoves")));
        DecisionRecord record = onlyRecord();
        assertThat(record.outcome()).isEqualTo(new Outcome.Sent(EDGE_MESSAGE));
        assertThat(record.versions().model()).isEqualTo(CLAUDE_MODEL);
        assertThat(record.exchanges()).hasSize(2);
        ModelExchange skipped = record.exchanges().getFirst();
        assertThat(skipped.provider()).isEqualTo("openai");
        assertThat(skipped.model()).isEqualTo("gpt-5.6-luna");
        assertThat(skipped.checkFailures()).singleElement().asString()
                .contains("could not be reached");
        ModelExchange answered = record.exchanges().getLast();
        assertThat(answered.provider()).isEqualTo("anthropic");
        assertThat(answered.model()).isEqualTo(CLAUDE_MODEL);
        assertThat(answered.checkFailures()).isEmpty();
        assertThat(answered.request()).isEqualTo(skipped.request());
    }

    @Test
    void geminiAnswersWhenOpenAiAndClaudeAreDown() {
        openAiIsDown();
        claudeIsDown();
        geminiAnswers(EDGE_ANSWER);

        checkWithAnEdge();

        edgeMessageWasSent();
        gemini.verify(1, postRequestedFor(urlPathMatching(".*/models/" + GEMINI_MODEL
                + ":generateContent")).withRequestBody(containing("legalMoves")));
        DecisionRecord record = onlyRecord();
        assertThat(record.versions().model()).isEqualTo(GEMINI_MODEL);
        assertThat(record.exchanges()).extracting(ModelExchange::provider)
                .containsExactly("openai", "anthropic", "gemini");
        assertThat(record.exchanges().subList(0, 2)).allSatisfy(exchange ->
                assertThat(exchange.checkFailures()).isNotEmpty());
        assertThat(record.exchanges().getLast().checkFailures()).isEmpty();
    }

    @Test
    void theCodeFallbackIsUsedWhenAllThreeAreDown() {
        openAiIsDown();
        claudeIsDown();
        geminiIsDown();
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-illegal.json", "rosters-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-mccaffrey-out.json", "players-v1");
        OutboundStubs.telegramOk(telegram);

        checkRunner.runCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text",
                        containing("Christian McCaffrey is Out and cannot play"))));
        claude.verify(postRequestedFor(urlEqualTo(ANTHROPIC_MESSAGES)));
        gemini.verify(postRequestedFor(urlPathMatching(GEMINI_GENERATE)));
        assertThat(records()).isNotEmpty().allSatisfy(record -> {
            assertThat(record.outcome()).isInstanceOf(Outcome.Sent.class);
            assertThat(record.versions().model()).isEqualTo("none");
            assertThat(record.exchanges()).isNotEmpty().allSatisfy(exchange ->
                    assertThat(exchange.checkFailures()).isNotEmpty());
        });
    }

    @Test
    void aFallbackAnswerThatFailsTheCheckGetsOneCorrectionFromTheSameProvider() {
        openAiIsDown();
        claudeAnswers(WRONG_NUMBER, EDGE_ANSWER);
        geminiAnswers(EDGE_ANSWER);

        checkWithAnEdge();

        edgeMessageWasSent();
        claude.verify(2, postRequestedFor(urlEqualTo(ANTHROPIC_MESSAGES)));
        claude.verify(1, postRequestedFor(urlEqualTo(ANTHROPIC_MESSAGES))
                .withRequestBody(containing("Your answer failed these checks")));
        gemini.verify(0, postRequestedFor(urlPathMatching(GEMINI_GENERATE)));
        DecisionRecord record = onlyRecord();
        assertThat(record.exchanges()).extracting(ModelExchange::provider)
                .containsExactly("openai", "anthropic", "anthropic");
        assertThat(record.exchanges().get(1).checkFailures())
                .anySatisfy(failure -> assertThat(failure).contains("18.0"));
        assertThat(record.exchanges().get(2).attempt()).isEqualTo(2);
        assertThat(record.exchanges().get(2).checkFailures()).isEmpty();
    }

    @Test
    void aFallbackAnswerThatFailsTwiceIsWithheldWithoutAskingTheNextProvider() {
        openAiIsDown();
        claudeAnswers(WRONG_NUMBER, WRONG_NUMBER);
        geminiAnswers(EDGE_ANSWER);

        checkWithAnEdge();

        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        gemini.verify(0, postRequestedFor(urlPathMatching(GEMINI_GENERATE)));
        DecisionRecord record = onlyRecord();
        assertThat(record.outcome()).isInstanceOf(Outcome.Withheld.class);
        assertThat(record.versions().model()).isEqualTo(CLAUDE_MODEL);
    }

    @Test
    void aReplyThatIsNotJsonSkipsToTheNextProvider() {
        OutboundStubs.llmAdvises(llm, EDGE_MESSAGE);
        claudeAnswers(EDGE_ANSWER);

        checkWithAnEdge();

        edgeMessageWasSent();
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH)));
        DecisionRecord record = onlyRecord();
        assertThat(record.exchanges()).extracting(ModelExchange::provider)
                .containsExactly("openai", "anthropic");
        assertThat(record.exchanges().getFirst().checkFailures())
                .anySatisfy(failure -> assertThat(failure).contains("JSON"));
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

    private DecisionRecord onlyRecord() {
        List<DecisionRecord> records = records();
        assertThat(records).hasSize(1);
        return records.getFirst();
    }

    private List<DecisionRecord> records() {
        Path league = Path.of(properties.storageDir(), "decision-records", "records",
                "user=" + USER_ID, "league=" + LEAGUE_ID);
        if (!Files.exists(league)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(league)) {
            return files
                    .map(file -> file.getFileName().toString())
                    .map(name -> name.substring("run=".length(), name.length() - ".json".length()))
                    .map(runId -> decisionRecords.read(USER_ID, LEAGUE_ID, runId).orElseThrow())
                    .sorted(Comparator.comparing(DecisionRecord::startedAt))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
