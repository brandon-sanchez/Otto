package otto.telegram;

import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import otto.OttoProperties;
import otto.http.OutboundHttp;
import otto.storage.JsonStore;
import otto.storage.OttoJson;

/**
 * Local stand-in for Telegram's webhook delivery. The deployed app is
 * reached at a Lambda function URL; a laptop has no such address, so
 * under the local profile Otto asks Telegram for its own updates - the
 * getUpdates long poll - and hands each one to
 * {@link TelegramWebhook#handle}, the seam the function URL also calls.
 *
 * <p>Long polling registers nothing with Telegram and serves no port of
 * its own, so it never rewrites the deployed assistant's front door and
 * puts no HTTP server into the deployable. Telegram delivers to a
 * webhook or to getUpdates and never to both, so a bot that has a
 * webhook registered answers 409 here until the operator clears it.
 * getUpdates carries no secret-token header, so the driver hands the
 * configured secret straight to the seam.
 */
@Component
@Profile("local")
public class LocalTelegramLoop {

    private static final Logger log = LoggerFactory.getLogger(LocalTelegramLoop.class);

    /** How long Telegram holds a poll open when it has nothing to give. */
    private static final Duration LONG_POLL = Duration.ofSeconds(25);

    /** The read timeout must outlast the long poll, or every poll fails. */
    private static final Duration READ_TIMEOUT = LONG_POLL.plusSeconds(10);

    /** The pause after a failed poll, so an outage cannot spin the loop. */
    private static final Duration BACKOFF = Duration.ofSeconds(5);

    /**
     * The pause between polls. Telegram holds an empty poll open for the
     * long-poll window, so this normally costs nothing; it is here so a
     * host that answers at once cannot turn the loop into a flood.
     */
    private static final Duration IDLE = Duration.ofSeconds(1);

    /** How long a stop waits for the poll in flight to end. */
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(2);

    /** Free text and button taps are all the assistant answers. */
    private static final String ALLOWED_UPDATES = "[\"message\",\"callback_query\"]";

    private static final String DOCUMENT = "telegram-updates";

    private final RestClient http;
    private final String botToken;
    private final String chatId;
    private final String webhookSecret;
    private final TelegramWebhook webhook;
    private final JsonStore store;

    private volatile boolean stopped;
    private volatile Thread poller;
    private volatile long lastUpdateId;

    public LocalTelegramLoop(OttoProperties properties, TelegramWebhook webhook, JsonStore store) {
        this.http = RestClient.builder()
                .baseUrl(properties.telegram().baseUrl())
                .requestFactory(OutboundHttp.requestFactory(READ_TIMEOUT))
                .build();
        this.botToken = properties.telegram().botToken();
        this.chatId = properties.telegram().chatId();
        this.webhookSecret = properties.telegram().webhookSecret();
        this.webhook = webhook;
        this.store = store;
        this.lastUpdateId = store.read(DOCUMENT, Watermark.class)
                .map(Watermark::lastUpdateId)
                .orElse(0L);
    }

    /**
     * Polling starts once the context is up. It runs on a thread of its
     * own, because a poll blocks for half a minute and the scheduler
     * that drives the Check has one thread to give.
     */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (botToken.isBlank() || chatId.isBlank() || webhookSecret.isBlank()) {
            log.warn("Telegram polling is off: set TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID"
                    + " and TELEGRAM_WEBHOOK_SECRET to reach the Ask loop");
            return;
        }
        if (poller != null) {
            return;
        }
        stopped = false;
        poller = Thread.ofVirtual().name("telegram-long-poll").start(this::pollUntilStopped);
        log.info("Telegram polling started: the bot answers chat {}", chatId);
    }

    /**
     * Stopping waits for the poll in flight, so no answer can arrive
     * after the context that owns the driver has gone. The wait is
     * bounded, so the stopped flag - not the wait - is what keeps a
     * late-returning poll from answering anything.
     */
    @PreDestroy
    public synchronized void stop() {
        stopped = true;
        Thread thread = poller;
        poller = null;
        if (thread == null) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(SHUTDOWN_WAIT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * One poll: read the updates Telegram holds and hand each one on.
     * Public so a test can drive a single poll without the thread.
     *
     * @return true when Telegram answered, false when the wire failed
     */
    public boolean poll() {
        Optional<JsonNode> waiting = fetchUpdates();
        if (waiting.isEmpty()) {
            return false;
        }
        for (JsonNode update : waiting.get()) {
            if (!dispatch(update)) {
                break;
            }
        }
        return true;
    }

    private void pollUntilStopped() {
        while (!stopped) {
            pause(poll() ? IDLE : BACKOFF);
        }
    }

    private void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopped = true;
        }
    }

    /**
     * An update Telegram has given us is answered at most once. The
     * driver confirms it before it answers it, never after: a crash
     * between the two costs one reply, where the other order would ask
     * the model the same question again and text the user twice. A
     * failure while answering is caught here for the same reason, so one
     * bad update cannot wedge the loop by coming back for ever.
     *
     * @return false when the rest of the batch must wait for a later
     *         poll: the driver is stopping, or the watermark did not store
     */
    private boolean dispatch(JsonNode update) {
        if (stopped) {
            return false;
        }
        long updateId = update.path("update_id").asLong(0);
        if (updateId <= lastUpdateId) {
            log.debug("Update {} was answered already", updateId);
            return true;
        }
        if (!confirm(updateId)) {
            return false;
        }
        try {
            if (webhook.handle(webhookSecret, update.toString()) != WebhookResult.OK) {
                log.warn("Update {} was refused: the configured webhook secret is not"
                        + " the one the seam holds", updateId);
            }
        } catch (Exception e) {
            log.warn("Update {} was dropped: {}: {}", updateId,
                    e.getClass().getSimpleName(), redactToken(e.getMessage()));
        }
        return true;
    }

    /**
     * The watermark is stored before the driver holds it, so an update
     * counts as confirmed only once the store has taken it. Telegram's
     * own offset stops it offering a confirmed update again while the
     * driver runs, but a driver that came up with no memory would be
     * offered the last unconfirmed update a second time.
     *
     * @return false when the watermark did not store, which leaves this
     *         update and the ones behind it for a later poll
     */
    private boolean confirm(long updateId) {
        try {
            store.write(DOCUMENT, new Watermark(updateId));
        } catch (RuntimeException e) {
            log.warn("Update {} is left for a later poll: the watermark did not store: {}",
                    updateId, e.getMessage());
            return false;
        }
        lastUpdateId = updateId;
        return true;
    }

    private Optional<JsonNode> fetchUpdates() {
        try {
            String body = http.get()
                    .uri(uri -> {
                        uri.path("/bot{token}/getUpdates")
                                .queryParam("timeout", LONG_POLL.toSeconds())
                                .queryParam("allowed_updates", ALLOWED_UPDATES);
                        if (lastUpdateId > 0) {
                            uri.queryParam("offset", lastUpdateId + 1);
                        }
                        return uri.build(botToken);
                    })
                    .retrieve()
                    .body(String.class);
            JsonNode answer = OttoJson.MAPPER.readTree(body == null ? "" : body);
            if (!answer.path("ok").asBoolean(false)) {
                log.warn("Telegram refused getUpdates: {}",
                        answer.path("description").asText("no detail"));
                return Optional.empty();
            }
            return Optional.of(answer.path("result"));
        } catch (HttpClientErrorException.Conflict e) {
            log.warn("Telegram answers getUpdates with 409 while the bot has a webhook."
                    + " Clear it with deleteWebhook to read messages locally.");
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Telegram getUpdates failed: {}: {}",
                    e.getClass().getSimpleName(), redactToken(e.getMessage()));
            return Optional.empty();
        }
    }

    /** An I/O failure message carries the request URI, and so the token. */
    private String redactToken(String message) {
        if (message == null) {
            return "no detail";
        }
        return botToken.isBlank() ? message : message.replace(botToken, "***");
    }

    /** The last update Telegram has given us, as it is stored. */
    public record Watermark(long lastUpdateId) {
    }
}
