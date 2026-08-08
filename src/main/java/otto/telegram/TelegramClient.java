package otto.telegram;

import java.time.Duration;
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
        try {
            http.post()
                    .uri("/bot{token}/sendMessage", botToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("chat_id", chatId, "text", text))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("Telegram send failed: {}: {}",
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
