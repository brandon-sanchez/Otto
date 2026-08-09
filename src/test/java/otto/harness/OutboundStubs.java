package otto.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import otto.storage.OttoJson;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

/** Stubs for the outbound wires: Telegram and the LLM. */
public final class OutboundStubs {

    public static final String SEND_MESSAGE_PATH = "/bottest-bot-token/sendMessage";
    public static final String ANSWER_CALLBACK_PATH = "/bottest-bot-token/answerCallbackQuery";
    public static final String CHAT_COMPLETIONS_PATH = "(/v1)?/chat/completions";

    /** The canned Lane A run: one tool call, then the narration. */
    private static final String TOOL_RUN = "lane-a-tool-run";
    private static final String TOOL_CALLED = "tool-called";

    private OutboundStubs() {
    }

    /** Escapes a phrase into a valid JSON string literal. */
    private static String jsonString(String phrase) {
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

    /** One user tap on an inline button, as Telegram posts it to the webhook. */
    public static String callbackTap(String callbackData) {
        return """
                {
                  "update_id": 200,
                  "callback_query": {
                    "id": "cb-1",
                    "from": {"id": 4242, "is_bot": false, "first_name": "Brandon"},
                    "message": {"message_id": 10},
                    "data": %s
                  }
                }
                """.formatted(jsonString(callbackData));
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

    private static String contentBody(String phrase) {
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

    private static String toolCallBody(String tool, String arguments) {
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
                        "tool_calls": [
                          {
                            "id": "call-1",
                            "type": "function",
                            "function": {"name": %s, "arguments": %s}
                          }
                        ]
                      },
                      "finish_reason": "tool_calls"
                    }
                  ],
                  "usage": {"prompt_tokens": 120, "completion_tokens": 25, "total_tokens": 145}
                }
                """.formatted(jsonString(tool), jsonString(arguments));
    }
}
