package otto.alerts;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import otto.events.Event;
import otto.events.EventLog;
import otto.storage.JsonStore;
import otto.telegram.TelegramClient;

/**
 * The write-through delivery state for Alerts. Pending deliveries retry
 * with their original id; sent deliveries suppress Telegram even when
 * their batched Event Log entries did not survive the Check.
 */
@Component
public class AlertDeliveryOutbox {

    private static final String KEY_PREFIX = "alert-delivery-key:";
    private static final String ID_PREFIX = "alert-delivery-id:";

    private enum Status {
        PENDING,
        SENT
    }

    private record KeyPointer(long alertId) {
    }

    private record Delivery(long alertId, Status status, String text, List<Event> events) {
        private Delivery {
            events = List.copyOf(events);
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
     * Owns the pending, send, sent and history ordering for every Alert path.
     * Existing pending deliveries retry separately, preserving their exact
     * text, event set and id even when today's candidates would merge them.
     */
    public synchronized List<Event> deliver(String text, List<Event> templates) {
        List<Event> appended = new ArrayList<>();
        Set<String> covered = new LinkedHashSet<>();
        Map<Long, Delivery> pending = new LinkedHashMap<>();
        for (Event template : templates) {
            readByKey(template.key())
                    .filter(delivery -> delivery.status() == Status.PENDING)
                    .ifPresent(delivery -> pending.putIfAbsent(delivery.alertId(), delivery));
        }
        for (Delivery delivery : pending.values()) {
            covered.addAll(delivery.events().stream().map(Event::key).toList());
            writePointers(delivery);
            appended.addAll(attempt(delivery));
        }

        List<Event> fresh = templates.stream()
                .filter(event -> !covered.contains(event.key()))
                .toList();
        if (!fresh.isEmpty()) {
            appended.addAll(attempt(prepare(text, fresh)));
        }
        return List.copyOf(appended);
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

    private Delivery prepare(String text, List<Event> templates) {
        long alertId = ids.next();
        List<Event> events = templates.stream()
                .map(event -> withAlertId(event, alertId))
                .toList();
        Delivery delivery = new Delivery(alertId, Status.PENDING, text, events);
        writePointers(delivery);
        store.write(ID_PREFIX + alertId, delivery);
        return delivery;
    }

    private void writePointers(Delivery delivery) {
        for (Event event : delivery.events()) {
            store.write(keyDocument(event.key()), new KeyPointer(delivery.alertId()));
        }
    }

    private List<Event> attempt(Delivery delivery) {
        if (!telegram.sendAlert(delivery.text(), delivery.alertId())) {
            return List.of();
        }
        Delivery sent = new Delivery(delivery.alertId(), Status.SENT,
                delivery.text(), delivery.events());
        store.write(ID_PREFIX + delivery.alertId(), sent);
        return sent.events().stream().filter(eventLog::append).toList();
    }

    private Optional<Delivery> readByKey(String key) {
        return store.read(keyDocument(key), KeyPointer.class)
                .flatMap(pointer -> readById(pointer.alertId()));
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
