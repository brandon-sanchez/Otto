package otto.delivery;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.events.Event;
import otto.events.EventLog;
import otto.scope.LeagueScope;
import otto.storage.JsonStore;
import otto.storage.Lease;
import otto.telegram.TelegramClient;
import otto.telegram.TelegramText;

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
 *
 * <p>Two Checks can overlap when the scheduled function has no reserved
 * concurrency, each in its own container with its own outbox. A
 * delivery therefore first takes a lease on each of its keys in the
 * shared store, which grants each lease to one holder at a time, and only the
 * outbox holding every lease sends. The other leaves the Alert alone:
 * the next Check finds it sent, or still pending and re-checks it.
 */
@Component
public class AlertDeliveryOutbox {

    private static final String KEY_PREFIX = "alert-delivery-key:";
    private static final String ID_PREFIX = "alert-delivery-id:";
    private static final String LEASE_PREFIX = "alert-delivery-lease:";

    /**
     * How long a lease keeps another container out when its holder
     * neither releases it nor finishes. Far longer than sending one
     * Alert's parts, and short enough that a container that died holding
     * it delays the Alert by a few Checks at most.
     */
    private static final Duration LEASE = Duration.ofMinutes(2);

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
     * @param leagueId the league the Alert is about, the config league's
     *        when it was sent with no league bound; null in a delivery
     *        stored before deliveries named their league, which was about
     *        the config league
     */
    private record Delivery(long alertId, Status status, String text, String basis,
            List<Event> events, List<String> parts, int partsSent, boolean viewAllOptions,
            String leagueId) {
        private Delivery {
            events = List.copyOf(events);
            parts = parts == null ? List.of(text) : List.copyOf(parts);
        }

        boolean covers(String key) {
            return events.stream().anyMatch(event -> event.key().equals(key));
        }

        Delivery with(Status next) {
            return new Delivery(alertId, next, text, basis, events, parts, partsSent,
                    viewAllOptions, leagueId);
        }

        Delivery sentThrough(int sent) {
            return new Delivery(alertId, status, text, basis, events, parts, sent,
                    viewAllOptions, leagueId);
        }
    }

    private final JsonStore store;
    private final AlertIdSequence ids;
    private final TelegramClient telegram;
    private final EventLog eventLog;
    private final Clock clock;
    private final String configLeagueId;

    /** This outbox's lease holder id: one per container. */
    private final String holder = UUID.randomUUID().toString();

    public AlertDeliveryOutbox(JsonStore store, AlertIdSequence ids,
            TelegramClient telegram, EventLog eventLog, Clock clock,
            OttoProperties properties) {
        this.store = store;
        this.ids = ids;
        this.telegram = telegram;
        this.eventLog = eventLog;
        this.clock = clock;
        this.configLeagueId = properties.leagueId();
    }

    /**
     * Sends one Alert covering every template's key and records it.
     *
     * <p>When a delivery for any of these keys is still pending from
     * the same basis, its text goes out as it stands and {@code compose}
     * is not called, so a retry after a transport failure costs no model
     * call.
     *
     * <p>When an earlier attempt for any of these keys is still
     * pending, this Alert is its re-checked version: it takes over that
     * delivery's id, so the buttons under it act on the same Alert, and
     * replaces its text and keys. Any further pending delivery among
     * the keys is cancelled, so the problems they named go out once, in
     * this message.
     *
     * <p>Side effects: {@code compose} when no pending text matches, a
     * lease write per key, store writes before and after one Telegram
     * send, and Event Log appends once Telegram accepts the message.
     *
     * @param compose writes the Alert's text; it runs outside the
     *        outbox's lock and before any lease is taken
     * @param templates the Events to record, one per Alert key; the
     *        Alert id and the sent text are added to their facts
     * @return the events appended to the Event Log, empty when Telegram
     *         did not accept the message, another container is delivering
     *         one of these keys, or one of them was sent already
     */
    public List<Event> deliver(DeliveryBasis basis, Supplier<String> compose,
            List<Event> templates) {
        String text = pendingText(templates.stream().map(Event::key).toList(), basis)
                .orElseGet(compose);
        return deliver(TelegramText.parts(text), false, basis, templates);
    }

    /**
     * Sends one Alert as several Telegram messages, in order, with the
     * buttons under the last, and records it as
     * {@link #deliver(DeliveryBasis, Supplier, List)} does. A retry
     * resumes after the last part Telegram accepted, as long as those
     * parts are unchanged; a part that changed sends the Alert again
     * from the top.
     *
     * <p>Side effects: a store write after each part Telegram accepts.
     *
     * @param parts the messages, each inside Telegram's length limit
     * @param viewAllOptions whether the buttons include View all options,
     *        whose tap arrives as "all:" and the Alert id
     */
    public synchronized List<Event> deliver(List<String> parts, boolean viewAllOptions,
            DeliveryBasis basis, List<Event> templates) {
        List<String> leased = new ArrayList<>();
        try {
            for (Event template : templates) {
                if (!lease(template.key())) {
                    log.info("Alert {} is being delivered by another run; leaving it there",
                            template.key());
                    return List.of();
                }
                leased.add(template.key());
            }
            if (templates.stream().map(Event::key).anyMatch(this::alreadySent)) {
                return List.of();
            }
            return deliverLeased(parts, viewAllOptions, basis.value(), templates);
        } finally {
            leased.forEach(key -> store.release(LEASE_PREFIX + encoded(key), holder));
        }
    }

    private List<Event> deliverLeased(List<String> parts, boolean viewAllOptions,
            String basis, List<Event> templates) {
        String text = String.join("\n\n", parts);
        List<Delivery> waiting = pending(templates.stream().map(Event::key).toList());
        long alertId = waiting.isEmpty() ? ids.next() : waiting.getFirst().alertId();
        waiting.stream().skip(1).forEach(superseded -> cancel(superseded,
                "merged into Alert " + alertId));
        List<Event> events = templates.stream()
                .map(event -> recorded(event, alertId, text))
                .toList();
        int partsSent = waiting.isEmpty() ? 0 : unchangedPartsSent(waiting.getFirst(), parts);
        Delivery delivery = new Delivery(alertId, Status.PENDING, text, basis, events, parts,
                partsSent, viewAllOptions, LeagueScope.bound().orElse(configLeagueId));
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

    /**
     * Returns the league the Alert with this id is about, so a tap on
     * its buttons acts there. Empty when no delivery has this id, or
     * when it was stored before deliveries named their league, which
     * makes it the config league's.
     */
    public Optional<String> leagueOf(long alertId) {
        return readById(alertId).map(Delivery::leagueId);
    }

    private boolean lease(String key) {
        Instant now = clock.instant();
        return store.lease(LEASE_PREFIX + encoded(key), new Lease(holder, now.plus(LEASE)), now);
    }

    private synchronized Optional<String> pendingText(List<String> keys, DeliveryBasis basis) {
        return pending(keys).stream()
                .filter(delivery -> basis.value().equals(delivery.basis()))
                .map(Delivery::text)
                .findFirst();
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

    private static Event recorded(Event event, long alertId, String text) {
        Map<String, String> facts = new LinkedHashMap<>(event.facts());
        facts.put("alertId", String.valueOf(alertId));
        facts.put("text", text);
        return new Event(event.key(), event.type(), event.at(), Map.copyOf(facts));
    }

    static String keyDocument(String key) {
        return KEY_PREFIX + encoded(key);
    }

    private static String encoded(String key) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }
}
