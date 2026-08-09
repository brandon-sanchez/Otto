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
 *
 * <p>The token guards the endpoint and the chat gate guards the
 * identity, so both are needed: anybody who holds the token reaches
 * this seam, and only the one chat the assistant serves is answered.
 * The gate is applied once, to the whole update, so no branch of
 * {@link #handle} can be added without it.
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
        parse(updateJson).ifPresent(this::dispatch);
        return WebhookResult.OK;
    }

    /**
     * The one chat gate, applied to every update before it is read for
     * what it asks. The secret token guards the endpoint; this guards
     * the identity, so anybody who holds the token still acts on no
     * Alert and asks no question of the assistant. Gating here rather
     * than in each branch is what keeps a later branch from missing it.
     */
    private void dispatch(JsonNode update) {
        JsonNode callback = update.path("callback_query");
        boolean isTap = !callback.isMissingNode();
        if (!fromConfiguredChat(update)) {
            log.warn("Update dropped: it is not from the chat the assistant serves");
            if (isTap) {
                acknowledge(callback, "I do not answer this chat.");
            }
            return;
        }
        if (isTap) {
            answerTap(callback);
            return;
        }
        answerAsk(update.path("message"));
    }

    /**
     * Telegram carries the chat on the message the update belongs to -
     * the message itself for free text, the message the button hangs on
     * for a tap. An update that names no chat cannot pass: the sender it
     * does name is a user, and a user is not the chat the assistant
     * serves. Telegram leaves the message out of a tap on one too old to
     * quote, so the cost of that is a refused tap on a stale Alert.
     */
    private boolean fromConfiguredChat(JsonNode update) {
        if (chatId == null || chatId.isBlank()) {
            return false;
        }
        JsonNode tap = update.path("callback_query");
        JsonNode message = tap.isMissingNode() ? update.path("message") : tap.path("message");
        return chatId.equals(message.path("chat").path("id").asText(""));
    }

    /** Free text goes to the Ask loop and its reply back to the chat. */
    private void answerAsk(JsonNode message) {
        String text = message.path("text").asText("");
        if (text.isBlank()) {
            return;
        }
        telegram.sendMessage(ask.answer(text));
    }

    private void answerTap(JsonNode callback) {
        Matcher tap = TAP.matcher(callback.path("data").asText(""));
        String ack = tap.matches()
                ? alertActions.apply(tap.group(1), Long.parseLong(tap.group(2)))
                        .orElse("That alert is no longer on my log.")
                : "I do not recognize that button.";
        acknowledge(callback, ack);
    }

    /**
     * Every tap that reaches the seam is answered, the ones this chat
     * does not own included: Telegram offers a callback_query again
     * until an answerCallbackQuery confirms it, so a silent drop buys a
     * repeat rather than quiet.
     */
    private void acknowledge(JsonNode callback, String text) {
        String callbackId = callback.path("id").asText("");
        if (callbackId.isEmpty()) {
            return;
        }
        telegram.answerCallbackQuery(callbackId, text);
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
