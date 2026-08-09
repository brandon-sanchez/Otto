package otto.telegram;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.alerts.AlertActions;
import otto.ask.AskService;
import otto.storage.OttoJson;

/**
 * The Telegram webhook entry point. Telegram posts every update here
 * and echoes the secret token set at webhook registration in the
 * X-Telegram-Bot-Api-Secret-Token header; a request that does not
 * carry the right token is rejected before any parsing. Invoked
 * directly by wire-seam tests; the deployed HTTP wrapper delegates to
 * it and maps the result to a status code.
 */
@Component
public class TelegramWebhook {

    private static final Logger log = LoggerFactory.getLogger(TelegramWebhook.class);

    private static final Pattern TAP = Pattern.compile("(done|ignore|mute):(\\d{1,18})");

    private final String webhookSecret;
    private final String chatId;
    private final AlertActions alertActions;
    private final AskService ask;
    private final TelegramClient telegram;

    public TelegramWebhook(OttoProperties properties, AlertActions alertActions,
            AskService ask, TelegramClient telegram) {
        this.webhookSecret = properties.telegram().webhookSecret();
        this.chatId = properties.telegram().chatId();
        this.alertActions = alertActions;
        this.ask = ask;
        this.telegram = telegram;
    }

    public WebhookResult handle(String secretToken, String updateJson) {
        if (!authorized(secretToken)) {
            log.warn("Webhook call rejected: bad or missing secret token");
            return WebhookResult.FORBIDDEN;
        }
        parse(updateJson).ifPresent(update -> {
            JsonNode callback = update.path("callback_query");
            if (!callback.isMissingNode()) {
                answerTap(callback);
                return;
            }
            answerAsk(update.path("message"));
        });
        return WebhookResult.OK;
    }

    /**
     * Free text goes to the Ask loop and its reply back to the chat.
     * Only the one chat the assistant serves is answered; anything else
     * that reaches this endpoint is dropped without a model call.
     */
    private void answerAsk(JsonNode message) {
        String text = message.path("text").asText("");
        if (text.isBlank() || !chatId.equals(message.path("chat").path("id").asText(""))) {
            return;
        }
        telegram.sendMessage(ask.answer(text));
    }

    private void answerTap(JsonNode callback) {
        String callbackId = callback.path("id").asText("");
        if (callbackId.isEmpty()) {
            return;
        }
        Matcher tap = TAP.matcher(callback.path("data").asText(""));
        String ack = tap.matches()
                ? alertActions.apply(tap.group(1), Long.parseLong(tap.group(2)))
                        .orElse("That alert is no longer on my log.")
                : "I do not recognize that button.";
        telegram.answerCallbackQuery(callbackId, ack);
    }

    /** A body Telegram would never send is logged and dropped, never retried. */
    private Optional<JsonNode> parse(String updateJson) {
        try {
            return Optional.of(OttoJson.MAPPER.readTree(updateJson));
        } catch (Exception e) {
            log.warn("Webhook update body is not JSON: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Constant-time comparison: the secret is the only guard on this
     * endpoint, so its check must not leak length or prefix timing.
     */
    private boolean authorized(String secretToken) {
        if (secretToken == null || webhookSecret == null || webhookSecret.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                secretToken.getBytes(StandardCharsets.UTF_8),
                webhookSecret.getBytes(StandardCharsets.UTF_8));
    }
}
