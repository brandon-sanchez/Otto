package otto.telegram;

/**
 * The outcome the webhook entry point reports to its HTTP wrapper.
 * Telegram only needs a status code back; the wrapper maps this enum.
 */
public enum WebhookResult {
    OK(200),
    FORBIDDEN(403);

    private final int status;

    WebhookResult(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }
}
