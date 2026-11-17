package otto.telegram;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.account.AccountDeletion;
import otto.alerts.AlertActions;
import otto.ask.AskService;
import otto.board.WaiverBoardOptions;
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
 * The gate is applied once, to the whole update, before the update is
 * read for what it asks. Every kind of update goes through that one
 * check, so a new kind cannot be added without it.
 */
@Component
public class TelegramWebhook {

    private static final Logger log = LoggerFactory.getLogger(TelegramWebhook.class);

    private static final Pattern TAP = Pattern.compile("(done|ignore|mute|all):(\\d{1,18})");

    /**
     * Telegram appends the bot's name to a command picked from its menu
     * in a group, so the suffix is accepted too.
     */
    private static final Pattern DELETE_ACCOUNT = Pattern.compile("/deleteaccount(@\\w+)?");

    private static final String VIEW_ALL = "all";

    private static final String COULD_NOT_SEND_ALL =
            "I couldn't send the full list. Tap the button again in a minute.";

    private final String webhookSecret;
    private final String chatId;
    private final AlertActions alertActions;
    private final WaiverBoardOptions boardOptions;
    private final AskService ask;
    private final AccountDeletion accountDeletion;
    private final TelegramClient telegram;

    public TelegramWebhook(OttoProperties properties, AlertActions alertActions,
            WaiverBoardOptions boardOptions, AskService ask, AccountDeletion accountDeletion,
            TelegramClient telegram) {
        this.webhookSecret = properties.telegram().webhookSecret();
        this.chatId = properties.telegram().chatId();
        this.alertActions = alertActions;
        this.boardOptions = boardOptions;
        this.ask = ask;
        this.accountDeletion = accountDeletion;
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
        if (isTap && callback.path("id").asText("").isEmpty()) {
            log.warn("Tap dropped: it carries no callback id, so it cannot be answered");
            return;
        }
        if (!fromConfiguredChat(update)) {
            if (isTap) {
                acknowledge(callback, refusalFor(callback));
                return;
            }
            log.warn("Update dropped: it is not from the chat the assistant serves");
            return;
        }
        if (isTap) {
            answerTap(callback);
            return;
        }
        answerAsk(update.path("message"));
    }

    /**
     * A refused tap is answered, so the wording has to be true. Only one
     * of the two refusals is about who tapped. Telegram stops quoting
     * the message a button hangs on once it is about two days old, and
     * the tap then names no chat, so the user's own tap on an Alert he
     * left for a day or two lands here. Telling him he is a stranger
     * would be false; the Alert is the thing that is out of date.
     */
    private String refusalFor(JsonNode callback) {
        if (callback.path("message").path("chat").path("id").asText("").isEmpty()) {
            log.warn("Tap dropped: it names no chat, so the alert it acts on is too old");
            return "That alert is too old to act on, so ask me about it instead.";
        }
        log.warn("Tap dropped: it is not from the chat the assistant serves");
        return "I don't answer this chat.";
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

    private void answerAsk(JsonNode message) {
        String text = message.path("text").asText("");
        if (text.isBlank()) {
            return;
        }
        if (DELETE_ACCOUNT.matcher(text.strip()).matches()) {
            AccountDeletion.Question question = accountDeletion.ask();
            telegram.sendWithButtons(question.text(), List.of(
                    new InlineButton("Confirm", question.confirmData()),
                    new InlineButton("Cancel", question.cancelData())));
            return;
        }
        telegram.sendMessage(ask.answer(text));
    }

    private void answerTap(JsonNode callback) {
        String data = callback.path("data").asText("");
        Optional<AccountDeletion.Answer> deletion = accountDeletion.answer(data);
        if (deletion.isPresent()) {
            acknowledge(callback, deletion.get().ack());
            deletion.get().message().ifPresent(telegram::sendMessage);
            return;
        }
        Matcher tap = TAP.matcher(data);
        if (!tap.matches()) {
            acknowledge(callback, "I don't recognize that button.");
            return;
        }
        long alertId = Long.parseLong(tap.group(2));
        String ack = VIEW_ALL.equals(tap.group(1))
                ? viewAllOptions(alertId)
                : alertActions.apply(tap.group(1), alertId)
                        .orElse("That alert is no longer on my log.");
        acknowledge(callback, ack);
    }

    /**
     * Sends the board's full list and says how it went. A failure to
     * read or record it is answered like a failed send: Telegram sends
     * again an update whose webhook call fails, and that retry would
     * fail the same way.
     */
    private String viewAllOptions(long alertId) {
        try {
            Optional<List<String>> options = boardOptions.unsentOptions(alertId);
            if (options.isEmpty()) {
                return "That board is no longer on my log.";
            }
            int delivered = 0;
            for (String part : options.get()) {
                if (!telegram.sendMessage(part)) {
                    break;
                }
                delivered++;
            }
            boolean finished = delivered == options.get().size();
            boardOptions.sent(alertId, delivered, finished);
            return finished ? "Here is every option on that board." : COULD_NOT_SEND_ALL;
        } catch (RuntimeException e) {
            log.error("Could not send the full list of board {}", alertId, e);
            return COULD_NOT_SEND_ALL;
        }
    }

    /**
     * Every tap the seam acts on is answered, the ones from another chat
     * included. Telegram offers a callback_query again until an
     * answerCallbackQuery confirms it, so a silent drop causes repeats.
     * A tap that carries no id is refused before this point, because
     * such a tap can never be answered.
     */
    private void acknowledge(JsonNode callback, String text) {
        telegram.answerCallbackQuery(callback.path("id").asText(""), text);
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
