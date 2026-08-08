package otto.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.tomakehurst.wiremock.WireMockServer;

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

    public static void llmPhrases(WireMockServer llm, String phrase) {
        String body = """
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
        llm.stubFor(post(urlPathMatching(CHAT_COMPLETIONS_PATH)).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
    }
}
