package otto.alerts;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import otto.events.Event;
import otto.events.EventLog;
import otto.storage.JsonStore;
import otto.telegram.TelegramClient;

/**
 * The write-through delivery state for Alerts. A delivery is pending
 * before Telegram is called and sent after Telegram accepts it; a sent
 * delivery suppresses Telegram even when its batched Event Log entries
 * did not survive the Check.
 *
 * <p>A pending delivery is never resent as stored. The next Check
 * rebuilds the Alert from what is true then and hands it back here,
 * so the delivery goes out unchanged, goes out rewritten under the
 * same id, or is cancelled when its advice no longer holds.
 */
@Component
public class AlertDeliveryOutbox {

    private static final String KEY_PREFIX = "alert-delivery-key:";
    private static final String ID_PREFIX = "alert-delivery-id:";

    private static final Logger log = LoggerFactory.getLogger(AlertDeliveryOutbox.class);

    private enum Status {
        PENDING,
        SENT,
        CANCELLED
    }

    private record KeyPointer(long alertId) {
    }

    /**
     * One Alert's delivery state.
     *
     * @param text every part joined, which a retry's text is compared with
     * @param parts the Telegram messages the Alert goes out as, in order,
     *        with the buttons under the last; null in a stored delivery
     *        written without parts, which goes out as its text alone
     * @param partsSent how many leading parts Telegram has accepted, so a
     *        retry never sends a part twice
     * @param viewAllOptions whether a View all options button joins the
     *        Done, Ignore and Mute buttons
     */
    private record Delivery(long alertId, Status status, String text, String basis,
            List<Event> events, List<String> parts, int partsSent, boolean viewAllOptions) {
        private Delivery {
            events = List.copyOf(events);
            parts = parts == null ? List.of(text) : List.copyOf(parts);
        }

        boolean covers(String key) {
            return events.stream().anyMatch(event -> event.key().equals(key));
        }

        Delivery with(Status next) {
            return new Delivery(alertId, next, text, basis, events, parts, partsSent,
                    viewAllOptions);
        }

        Delivery sentThrough(int sent) {
            return new Delivery(alertId, status, text, basis, events, parts, sent,
                    viewAllOptions);
        }
    }

    private final JsonStore store;
    private final AlertIdSequence ids;
    private final TelegramClient telegram;
    private final EventLog eventLog;

    public AlertDeliveryOutbox(JsonStore store, AlertIdSequence ids,
            TelegramClient telegram, EventLog eventLog) {
        this.store = store;
        this.ids = ids;
        this.telegram = telegram;
        this.eventLog = eventLog;
    }

    /**
     * Returns the text of a pending delivery for any of these keys
     * when it was written from the same basis. A caller that finds one
     * sends it as it stands instead of writing it again, so a retry
     * after a transport failure costs no model call.
     */
    public synchronized Optional<String> pendingText(List<String> keys, String basis) {
        return pending(keys).stream()
                .filter(delivery -> basis.equals(delivery.basis()))
                .map(Delivery::text)
                .findFirst();
    }

    /**
     * Sends one Alert covering every template's key and records it.
     *
     * <p>When an earlier attempt for any of these keys is still
     * pending, this Alert is its re-checked version: it takes over that
     * delivery's id, so the buttons under it act on the same Alert, and
     * replaces its text and keys. Any further pending delivery among
     * the keys is cancelled, so the problems they named go out once, in
     * this message.
     *
     * <p>Side effects: store writes before and after one Telegram send,
     * and Event Log appends once Telegram accepts the message.
     *
     * @param basis the fingerprint of what the text was written from,
     *        which {@link #pendingText} matches a retry against
     * @return the events appended to the Event Log, empty when Telegram
     *         did not accept the message
     */
    public List<Event> deliver(String text, String basis, List<Event> templates) {
        return deliver(List.of(text), false, basis, templates);
    }

    /**
     * Sends one Alert as several Telegram messages, in order, with the
     * buttons under the last, and records it as
     * {@link #deliver(String, String, List)} does. A retry resumes after
     * the last part Telegram accepted, as long as those parts are
     * unchanged; a part that changed sends the Alert again from the top.
     *
     * <p>Side effects: a store write after each part Telegram accepts.
     *
     * @param parts the messages, each inside Telegram's length limit
     * @param viewAllOptions whether the buttons include View all options,
     *        whose tap arrives as "all:" and the Alert id
     */
    public synchronized List<Event> deliver(List<String> parts, boolean viewAllOptions,
            String basis, List<Event> templates) {
        String text = String.join("\n\n", parts);
        List<Delivery> waiting = pending(templates.stream().map(Event::key).toList());
        long alertId = waiting.isEmpty() ? ids.next() : waiting.getFirst().alertId();
        waiting.stream().skip(1).forEach(superseded -> cancel(superseded,
                "merged into Alert " + alertId));
        List<Event> events = templates.stream()
                .map(event -> withAlertId(event, alertId))
                .toList();
        int partsSent = waiting.isEmpty() ? 0 : unchangedPartsSent(waiting.getFirst(), parts);
        Delivery delivery = new Delivery(alertId, Status.PENDING, text, basis, events, parts,
                partsSent, viewAllOptions);
        if (!waiting.isEmpty()) {
            Delivery earlier = waiting.getFirst();
            log.info("Re-checked delayed Alert {}: {}", alertId,
                    earlier.text().equals(text) && earlier.events().equals(events)
                            ? "sending unchanged" : "sending updated text");
        }
        writePointers(delivery);
        store.write(ID_PREFIX + alertId, delivery);
        return attempt(delivery);
    }

    /**
     * Cancels the pending deliveries for these keys: the Check that
     * re-checked them found their advice no longer holds. A key whose
     * problem comes back later is delivered as a new Alert.
     */
    public synchronized void cancel(List<String> keys, String reason) {
        pending(keys).forEach(delivery -> cancel(delivery, reason));
    }

    /** Restores history and reports whether Telegram accepted this stable key. */
    public boolean alreadySent(String key) {
        if (eventLog.contains(key)) {
            return true;
        }
        Optional<Delivery> delivery = readByKey(key)
                .filter(candidate -> candidate.status() == Status.SENT);
        delivery.ifPresent(candidate -> candidate.events().forEach(eventLog::append));
        return delivery.isPresent();
    }

    /** Sent Alert facts remain available to inline buttons before log recovery. */
    public List<Event> sentEvents(long alertId) {
        return readById(alertId)
                .filter(delivery -> delivery.status() == Status.SENT)
                .map(Delivery::events)
                .orElseGet(List::of);
    }

    private List<Delivery> pending(List<String> keys) {
        Map<Long, Delivery> pending = new LinkedHashMap<>();
        for (String key : keys) {
            readByKey(key)
                    .filter(delivery -> delivery.status() == Status.PENDING)
                    .ifPresent(delivery -> pending.putIfAbsent(delivery.alertId(), delivery));
        }
        return List.copyOf(pending.values());
    }

    private void cancel(Delivery delivery, String reason) {
        log.info("Cancelled delayed Alert {}: {}", delivery.alertId(), reason);
        store.write(ID_PREFIX + delivery.alertId(), delivery.with(Status.CANCELLED));
    }

    private void writePointers(Delivery delivery) {
        for (Event event : delivery.events()) {
            store.write(keyDocument(event.key()), new KeyPointer(delivery.alertId()));
        }
    }

    private static int unchangedPartsSent(Delivery earlier, List<String> parts) {
        int sent = earlier.partsSent();
        return sent < parts.size()
                && earlier.parts().subList(0, sent).equals(parts.subList(0, sent)) ? sent : 0;
    }

    private List<Event> attempt(Delivery delivery) {
        Delivery progress = delivery;
        int last = delivery.parts().size() - 1;
        while (progress.partsSent() < last) {
            if (!telegram.sendMessage(progress.parts().get(progress.partsSent()))) {
                return List.of();
            }
            progress = progress.sentThrough(progress.partsSent() + 1);
            store.write(ID_PREFIX + progress.alertId(), progress);
        }
        if (!telegram.sendAlert(progress.parts().get(last), progress.alertId(),
                progress.viewAllOptions())) {
            return List.of();
        }
        Delivery sent = progress.sentThrough(last + 1).with(Status.SENT);
        store.write(ID_PREFIX + delivery.alertId(), sent);
        return sent.events().stream().filter(eventLog::append).toList();
    }

    private Optional<Delivery> readByKey(String key) {
        return store.read(keyDocument(key), KeyPointer.class)
                .flatMap(pointer -> readById(pointer.alertId()))
                .filter(delivery -> delivery.covers(key));
    }

    private Optional<Delivery> readById(long alertId) {
        return store.read(ID_PREFIX + alertId, Delivery.class);
    }

    private static Event withAlertId(Event event, long alertId) {
        Map<String, String> facts = new LinkedHashMap<>(event.facts());
        facts.put("alertId", String.valueOf(alertId));
        return new Event(event.key(), event.type(), event.at(), Map.copyOf(facts));
    }

    static String keyDocument(String key) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.getBytes(StandardCharsets.UTF_8));
        return KEY_PREFIX + encoded;
    }
}
