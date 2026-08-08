package otto.telegram;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import otto.OttoProperties;
import otto.http.OutboundHttp;

/** The outbound wire to the user's Telegram chat. */
@Component
public class TelegramClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient http;
    private final String botToken;
    private final String chatId;

    public TelegramClient(OttoProperties properties) {
        this.http = RestClient.builder()
                .baseUrl(properties.telegram().baseUrl())
                .requestFactory(OutboundHttp.requestFactory(READ_TIMEOUT))
                .build();
        this.botToken = properties.telegram().botToken();
        this.chatId = properties.telegram().chatId();
    }

    /**
     * Sends one text message to the configured chat. A failure is
     * logged, never thrown, so callers decide whether to retry.
     *
     * @return true when Telegram accepted the message
     */
    public boolean sendMessage(String text) {
        return post("sendMessage", Map.of("chat_id", chatId, "text", text));
    }

    /**
     * Sends one Alert: a text message carrying the inline Done, Ignore,
     * and Mute buttons. The buttons reference the Alert's short id, so
     * a tap arrives on the webhook as "done:5" and stays inside
     * Telegram's 64-byte callback-data limit.
     *
     * @return true when Telegram accepted the message
     */
    public boolean sendAlert(String text, long alertId) {
        List<Map<String, String>> buttons = List.of(
                Map.of("text", "Done", "callback_data", "done:" + alertId),
                Map.of("text", "Ignore", "callback_data", "ignore:" + alertId),
                Map.of("text", "Mute", "callback_data", "mute:" + alertId));
        return post("sendMessage", Map.of(
                "chat_id", chatId,
                "text", text,
                "reply_markup", Map.of("inline_keyboard", List.of(buttons))));
    }

    /**
     * Acknowledges one button tap so the user's Telegram client stops
     * its loading spinner and shows the short ack text.
     */
    public boolean answerCallbackQuery(String callbackQueryId, String text) {
        return post("answerCallbackQuery", Map.of(
                "callback_query_id", callbackQueryId,
                "text", text));
    }

    private boolean post(String method, Map<String, Object> body) {
        try {
            http.post()
                    .uri("/bot{token}/{method}", botToken, method)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("Telegram {} failed: {}: {}", method,
                    e.getClass().getSimpleName(), redactToken(e.getMessage()));
            return false;
        }
    }

    /** The exception message can carry the request URI, and so the token. */
    private String redactToken(String message) {
        if (message == null) {
            return "no detail";
        }
        return botToken.isBlank() ? message : message.replace(botToken, "***");
    }
}
