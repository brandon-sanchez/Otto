package otto.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.storage.JsonStore;

/**
 * Deleting the user's saved advice history from the chat, in two steps.
 *
 * <p>{@link #ask} opens a deletion request and returns the question to
 * send, with Confirm and Cancel buttons. Each button carries a random
 * token for that one request, so a button can only answer the question
 * it hangs on. {@link #answer} deletes only on a Confirm whose token
 * matches the open request and arrives before the request expires;
 * Cancel, an expired request, a token that matches nothing and a second
 * tap on an answered question all delete nothing.
 *
 * <p>Who may tap is not decided here: the webhook's chat gate answers
 * only the one chat the assistant serves, before any button is read.
 */
@Component
public class AccountDeletion {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletion.class);

    static final Duration EXPIRY = Duration.ofMinutes(5);

    private static final String DOCUMENT = "account-deletion-request";

    private static final String CONFIRM = "confirm";
    private static final String CANCEL = "cancel";

    private static final Pattern BUTTON = Pattern.compile("account:(confirm|cancel):(.*)");

    private static final int TOKEN_BYTES = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String QUESTION = """
            Delete your saved advice history?

            This deletes every Decision Record of advice I've given you, the outcomes \
            I recorded for that advice, and the article text I stored with it, in every \
            league. It can't be undone.

            Your settings, Watchlist and league data stay. Advice I give after this is \
            saved as usual.

            Tap Confirm within %d minutes to delete, or Cancel to keep everything."""
            .formatted(EXPIRY.toMinutes());

    private static final String DELETED = """
            Done. I deleted all your saved advice history: every Decision Record, the \
            outcomes I recorded and the article text I stored. Advice I give from now on \
            is saved as usual.""";

    private static final String START_AGAIN = " Send /deleteaccount to start again.";

    private record Request(String token, Instant expiresAt) {

        static final Request NONE = new Request("", Instant.EPOCH);

        boolean matches(String tapped) {
            return !token.isEmpty() && MessageDigest.isEqual(
                    token.getBytes(StandardCharsets.UTF_8),
                    tapped.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * One button under the question.
     *
     * @param label the text on the button, which the question names
     * @param callbackData what a tap on it posts back
     */
    public record Choice(String label, String callbackData) {
    }

    /**
     * The question to send, with its Confirm and Cancel buttons in that
     * order.
     */
    public record Question(String text, List<Choice> choices) {

        public Question {
            choices = List.copyOf(choices);
        }
    }

    /**
     * What to tell the user after a tap.
     *
     * @param ack the short text shown on the tapped button
     * @param message a chat message to send as well, or empty when the
     *        ack says everything
     */
    public record Answer(String ack, Optional<String> message) {

        static Answer ack(String text) {
            return new Answer(text, Optional.empty());
        }
    }

    private final JsonStore store;
    private final RecordsEraser eraser;
    private final Clock clock;
    private final String userId;

    public AccountDeletion(JsonStore store, RecordsEraser eraser, Clock clock,
            OttoProperties properties) {
        this.store = store;
        this.eraser = eraser;
        this.clock = clock;
        this.userId = properties.userId();
    }

    /**
     * Opens a deletion request, replacing any request still open, and
     * returns the question that confirms it.
     *
     * <p>Side effects: writes the request to the store.
     */
    public Question ask() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        store.write(DOCUMENT, new Request(token, clock.instant().plus(EXPIRY)));
        return new Question(QUESTION, List.of(
                new Choice("Confirm", button(CONFIRM, token)),
                new Choice("Cancel", button(CANCEL, token))));
    }

    /**
     * Answers a tap on a Confirm or Cancel button.
     *
     * <p>Side effects: a valid Confirm deletes every Decision Record,
     * outcome record and stored article text of the user, then closes
     * the request; a valid Cancel closes it. A Confirm whose deletion
     * fails leaves the request open, so tapping Confirm again retries.
     *
     * @return the answer, or empty when the callback data is not one of
     *         these buttons
     */
    public Optional<Answer> answer(String callbackData) {
        Matcher tap = BUTTON.matcher(callbackData);
        if (!tap.matches()) {
            return Optional.empty();
        }
        Request open = store.read(DOCUMENT, Request.class).orElse(Request.NONE);
        if (!open.matches(tap.group(2))) {
            return Optional.of(Answer.ack(
                    "That request is no longer open, so nothing was deleted." + START_AGAIN));
        }
        if (!clock.instant().isBefore(open.expiresAt())) {
            return Optional.of(Answer.ack(
                    "That request expired, so nothing was deleted." + START_AGAIN));
        }
        return Optional.of(CONFIRM.equals(tap.group(1)) ? delete() : cancel());
    }

    private Answer delete() {
        try {
            eraser.eraseUser(userId);
        } catch (RuntimeException e) {
            log.error("Could not delete the user's advice history", e);
            return Answer.ack("I couldn't finish deleting. Tap Confirm again in a minute.");
        }
        store.write(DOCUMENT, Request.NONE);
        log.info("Deleted the user's advice history on request");
        return new Answer("Deleted.", Optional.of(DELETED));
    }

    private Answer cancel() {
        store.write(DOCUMENT, Request.NONE);
        return Answer.ack("Cancelled. Your advice history is still saved.");
    }

    private static String button(String action, String token) {
        return "account:" + action + ":" + token;
    }
}
