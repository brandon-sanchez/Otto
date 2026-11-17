package otto.telegram;

/**
 * One button under a message.
 *
 * @param text the label the user sees
 * @param callbackData what the tap posts back to the webhook; Telegram
 *        refuses more than 64 bytes
 */
public record InlineButton(String text, String callbackData) {
}
