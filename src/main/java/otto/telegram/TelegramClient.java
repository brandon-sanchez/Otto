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

/**
 * The outbound wire to the user's Telegram chat. Every message starts
 * with the {@link MessageHeading} in force when it is sent, so a message
 * about one of several leagues names its league.
 */
@Component
public class TelegramClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient http;
    private final String botToken;
    private final String chatId;
    private final MessageHeading heading;

    public TelegramClient(OttoProperties properties, MessageHeading heading) {
        this.http = RestClient.builder()
                .baseUrl(properties.telegram().baseUrl())
                .requestFactory(OutboundHttp.requestFactory(READ_TIMEOUT))
                .build();
        this.botToken = properties.telegram().botToken();
        this.chatId = properties.telegram().chatId();
        this.heading = heading;
    }

    /**
     * Sends text to the configured chat, tidied and split as
     * {@link TelegramText#parts} lays it out, one message per part in
     * order. A failure is logged, never thrown, so callers decide
     * whether to retry; sending stops at the first part Telegram refuses,
     * so a retry can repeat the parts already sent.
     *
     * @return true when Telegram accepted every part
     */
    public boolean sendMessage(String text) {
        for (String part : TelegramText.parts(headed(text))) {
            if (!post("sendMessage", Map.of("chat_id", chatId, "text", part))) {
                return false;
            }
        }
        return true;
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
        return sendAlert(text, alertId, false);
    }

    /**
     * Sends one Alert as {@link #sendAlert(String, long)} does, adding a
     * View all options button on a row of its own when asked. Its tap
     * arrives as "all:5". Text too long for one message goes out as
     * {@link #sendMessage} splits it, with the buttons on the last part.
     *
     * @return true when Telegram accepted every part
     */
    public boolean sendAlert(String text, long alertId, boolean viewAllOptions) {
        List<InlineButton> buttons = List.of(
                new InlineButton("Done", "done:" + alertId),
                new InlineButton("Ignore", "ignore:" + alertId),
                new InlineButton("Mute", "mute:" + alertId));
        List<List<InlineButton>> rows = viewAllOptions
                ? List.of(buttons, List.of(new InlineButton("View all options", "all:" + alertId)))
                : List.of(buttons);
        return sendWithKeyboard(text, rows);
    }

    /**
     * Sends text with one row of inline buttons under it. Text too long
     * for one message goes out as {@link #sendMessage} splits it, with
     * the buttons on the last part.
     *
     * @return true when Telegram accepted every part
     */
    public boolean sendWithButtons(String text, List<InlineButton> buttons) {
        return sendWithKeyboard(text, List.of(buttons));
    }

    private boolean sendWithKeyboard(String text, List<List<InlineButton>> rows) {
        List<String> parts = TelegramText.parts(headed(text));
        for (String part : parts.subList(0, parts.size() - 1)) {
            if (!post("sendMessage", Map.of("chat_id", chatId, "text", part))) {
                return false;
            }
        }
        List<List<Map<String, String>>> keyboard = rows.stream()
                .map(row -> row.stream()
                        .map(button -> Map.of("text", button.text(),
                                "callback_data", button.callbackData()))
                        .toList())
                .toList();
        return post("sendMessage", Map.of(
                "chat_id", chatId,
                "text", parts.getLast(),
                "reply_markup", Map.of("inline_keyboard", keyboard)));
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

    private String headed(String text) {
        return heading.current().map(line -> line + "\n\n" + text).orElse(text);
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
