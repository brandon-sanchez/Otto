package otto.harness;

import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.matching.StringValuePattern;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import otto.storage.OttoJson;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

/** Stubs for the outbound wires: Telegram and the LLM. */
public final class OutboundStubs {

    public static final String SEND_MESSAGE_PATH = "/bottest-bot-token/sendMessage";
    public static final String ANSWER_CALLBACK_PATH = "/bottest-bot-token/answerCallbackQuery";
    public static final String GET_UPDATES_PATH = "/bottest-bot-token/getUpdates";
    public static final String CHAT_COMPLETIONS_PATH = "(/v1)?/chat/completions";

    /** Text only a waiver board's advice request carries: the start of its task. */
    public static final String WAIVER_BOARD_REQUEST = "Rank this week's waiver candidates";

    private static final String ADVICE_REQUEST = "legalMoves";

    static final int ADVICE_PRIORITY = 1;

    private static final String ADVICE_RUN = "fact-checked-advice";

    /** The canned Lane A run: one tool call, then the narration. */
    private static final String TOOL_RUN = "lane-a-tool-run";
    private static final String TOOL_CALLED = "tool-called";

    private OutboundStubs() {
    }

    /**
     * Matches an Alert's text: one headline line, which code writes from
     * the kind of news, then a blank line and exactly the advice under it.
     */
    public static StringValuePattern underHeadline(String advice) {
        return matching("(?s)[^\\n]+\\n\\n" + Pattern.quote(advice));
    }

    /** Escapes a phrase into a valid JSON string literal. */
    public static String jsonString(String phrase) {
        try {
            return OttoJson.MAPPER.writeValueAsString(phrase);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot encode phrase", e);
        }
    }

    public static void telegramOk(WireMockServer telegram) {
        telegram.stubFor(post(urlEqualTo(SEND_MESSAGE_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"ok\":true,\"result\":{\"message_id\":1}}")));
    }

    public static void telegramCallbackAnswered(WireMockServer telegram) {
        telegram.stubFor(post(urlEqualTo(ANSWER_CALLBACK_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"ok\":true,\"result\":true}")));
    }

    /**
     * The long-poll answer the local driver reads: the updates Telegram
     * holds, in the order it hands them over.
     */
    public static void telegramUpdates(WireMockServer telegram, String... updates) {
        telegram.stubFor(get(urlPathEqualTo(GET_UPDATES_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"ok\":true,\"result\":[%s]}".formatted(String.join(",", updates)))));
    }

    /**
     * One user tap on an inline button, as Telegram posts it to the
     * webhook. Its update id is fixed, so two taps in one long-poll
     * answer would read as the same update: give a scenario that needs
     * two taps an update id of its own.
     */
    public static String callbackTap(String callbackData) {
        return callbackTap(WireSeamTest.USER_CHAT_ID, callbackData);
    }

    /** The same tap from any chat, to prove the chat gate works. */
    public static String callbackTap(long chatId, String callbackData) {
        return """
                {
                  "update_id": 200,
                  "callback_query": {
                    "id": "cb-1",
                    "from": {"id": %d, "is_bot": false, "first_name": "Brandon"},
                    "message": {"message_id": 10, "chat": {"id": %d, "type": "private"}},
                    "data": %s
                  }
                }
                """.formatted(chatId, chatId, jsonString(callbackData));
    }

    /** One free-text Ask from the user's chat, as Telegram posts it. */
    public static String textMessage(String text) {
        return textMessage(WireSeamTest.USER_CHAT_ID, text);
    }

    /** The same update from any chat, to prove the chat gate works. */
    public static String textMessage(long chatId, String text) {
        return """
                {
                  "update_id": 300,
                  "message": {
                    "message_id": 11,
                    "from": {"id": %d, "is_bot": false, "first_name": "Brandon"},
                    "chat": {"id": %d, "type": "private"},
                    "text": %s
                  }
                }
                """.formatted(chatId, chatId, jsonString(text));
    }

    public static void llmPhrases(WireMockServer llm, String phrase) {
        llm.stubFor(post(urlPathMatching(CHAT_COMPLETIONS_PATH))
                .willReturn(completion(contentBody(phrase))));
    }

    /**
     * The canned tool-call sequence that makes Lane A routing
     * deterministic: the first call answers with the tool call, the
     * second - the one carrying the tool result - answers with the
     * narration. No test spends tokens.
     */
    public static void llmCallsToolThenPhrases(WireMockServer llm, String tool,
            String arguments, String phrase) {
        llm.stubFor(post(urlPathMatching(CHAT_COMPLETIONS_PATH))
                .inScenario(TOOL_RUN)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(completion(toolCallBody(tool, arguments)))
                .willSetStateTo(TOOL_CALLED));
        llm.stubFor(post(urlPathMatching(CHAT_COMPLETIONS_PATH))
                .inScenario(TOOL_RUN)
                .whenScenarioStateIs(TOOL_CALLED)
                .willReturn(completion(contentBody(phrase))));
    }

    /**
     * The same canned run, but the first call answers with several tool
     * calls at once.
     *
     * @param calls each tool's name, then its JSON arguments, in turn
     */
    public static void llmCallsToolsAtOnceThenPhrases(WireMockServer llm, String phrase,
            String... calls) {
        llm.stubFor(post(urlPathMatching(CHAT_COMPLETIONS_PATH))
                .inScenario(TOOL_RUN)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(completion(toolCallBody(calls)))
                .willSetStateTo(TOOL_CALLED));
        llm.stubFor(post(urlPathMatching(CHAT_COMPLETIONS_PATH))
                .inScenario(TOOL_RUN)
                .whenScenarioStateIs(TOOL_CALLED)
                .willReturn(completion(contentBody(phrase))));
    }

    /**
     * The model's answers to fact-checked advice requests, in the order
     * it gives them; the last one repeats. Phrasing requests are left to
     * whatever {@link #llmPhrases} stubbed.
     */
    public static void llmAdvises(WireMockServer llm, String... answers) {
        for (int index = 0; index < answers.length; index++) {
            String state = index == 0 ? Scenario.STARTED : "answer-" + index;
            String next = index == answers.length - 1 ? state : "answer-" + (index + 1);
            llm.stubFor(post(urlPathMatching(CHAT_COMPLETIONS_PATH))
                    .atPriority(ADVICE_PRIORITY)
                    .withRequestBody(containing(ADVICE_REQUEST))
                    .inScenario(ADVICE_RUN)
                    .whenScenarioStateIs(state)
                    .willReturn(completion(contentBody(answers[index])))
                    .willSetStateTo(next));
        }
    }

    /**
     * The model's answer to every fact-checked advice request whose body
     * contains the given text.
     */
    public static void llmAdvisesOn(WireMockServer llm, String requestContains, String answer) {
        llm.stubFor(post(urlPathMatching(CHAT_COMPLETIONS_PATH))
                .atPriority(ADVICE_PRIORITY)
                .withRequestBody(containing(ADVICE_REQUEST))
                .withRequestBody(containing(requestContains))
                .willReturn(completion(contentBody(answer))));
    }

    /**
     * The model's answers, in the order it gives them, to every advice
     * request whose body contains the given text; the last one repeats.
     * Other advice requests are left to the other stubs.
     */
    public static void llmAdvisesInOrderOn(WireMockServer llm, String requestContains,
            String... answers) {
        String run = "advice-on-" + requestContains;
        for (int index = 0; index < answers.length; index++) {
            String state = index == 0 ? Scenario.STARTED : "answer-" + index;
            String next = index == answers.length - 1 ? state : "answer-" + (index + 1);
            llm.stubFor(post(urlPathMatching(CHAT_COMPLETIONS_PATH))
                    .atPriority(ADVICE_PRIORITY)
                    .withRequestBody(containing(ADVICE_REQUEST))
                    .withRequestBody(containing(requestContains))
                    .inScenario(run)
                    .whenScenarioStateIs(state)
                    .willReturn(completion(contentBody(answers[index])))
                    .willSetStateTo(next));
        }
    }

    /** The model is unreachable: a request error the SDK does not retry. */
    public static void llmRefuses(WireMockServer llm) {
        llm.stubFor(post(urlPathMatching(CHAT_COMPLETIONS_PATH)).willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"bad request\",\"type\":\"invalid_request_error\"}}")));
    }

    private static ResponseDefinitionBuilder completion(String body) {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }

    /** A chat-completions response body whose only message is the phrase. */
    public static String contentBody(String phrase) {
        return """
                {
                  "id": "chatcmpl-fixture",
                  "object": "chat.completion",
                  "created": 1757000000,
                  "model": "gpt-5.6-luna",
                  "choices": [
                    {
                      "index": 0,
                      "message": {"role": "assistant", "content": %s},
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {"prompt_tokens": 120, "completion_tokens": 25, "total_tokens": 145}
                }
                """.formatted(jsonString(phrase));
    }

    /**
     * A chat completion that calls tools.
     *
     * @param calls each tool's name, then its JSON arguments, in turn
     */
    public static String toolCallBody(String... calls) {
        StringBuilder toolCalls = new StringBuilder();
        for (int index = 0; index < calls.length; index += 2) {
            toolCalls.append(index == 0 ? "" : ",").append("""
                    {
                      "id": "call-%d",
                      "type": "function",
                      "function": {"name": %s, "arguments": %s}
                    }
                    """.formatted(index / 2 + 1, jsonString(calls[index]),
                    jsonString(calls[index + 1])));
        }
        return """
                {
                  "id": "chatcmpl-fixture-tool",
                  "object": "chat.completion",
                  "created": 1757000000,
                  "model": "gpt-5.6-luna",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": null,
                        "tool_calls": [%s]
                      },
                      "finish_reason": "tool_calls"
                    }
                  ],
                  "usage": {"prompt_tokens": 120, "completion_tokens": 25, "total_tokens": 145}
                }
                """.formatted(toolCalls);
    }
}
