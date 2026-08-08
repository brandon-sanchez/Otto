package otto.events;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JavaType;
import org.springframework.stereotype.Component;

import otto.storage.JsonStore;
import otto.storage.OttoJson;

/**
 * The season-long record of events. Injury history, Alert dedup, and
 * follow-up rules read from it. Exact duplicate keys never append twice.
 */
@Component
public class EventLog {

    private static final String DOCUMENT = "event-log";
    private static final JavaType LIST_TYPE =
            OttoJson.MAPPER.getTypeFactory().constructCollectionType(List.class, Event.class);

    private final JsonStore store;

    public EventLog(JsonStore store) {
        this.store = store;
    }

    public List<Event> all() {
        return store.<List<Event>>read(DOCUMENT, LIST_TYPE).orElseGet(List::of);
    }

    public boolean contains(String key) {
        return all().stream().anyMatch(event -> event.key().equals(key));
    }

    /**
     * Appends the event unless its key is already recorded.
     *
     * @return true when appended; false when the key is an exact duplicate
     */
    public synchronized boolean append(Event event) {
        List<Event> events = new ArrayList<>(all());
        if (events.stream().anyMatch(existing -> existing.key().equals(event.key()))) {
            return false;
        }
        events.add(event);
        store.write(DOCUMENT, events);
        return true;
    }
}
