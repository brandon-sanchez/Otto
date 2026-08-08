package otto.alerts;

import org.springframework.stereotype.Component;

import otto.storage.JsonStore;

/**
 * Hands out the short ids the inline buttons reference. The counter
 * persists before the send, so an id is never reused even when the
 * process dies between Telegram accepting a message and the Event Log
 * recording it. A send that never happens just leaves a gap.
 */
@Component
public class AlertIdSequence {

    private static final String DOCUMENT = "alert-id-sequence";

    private record Sequence(long last) {
    }

    private final JsonStore store;

    public AlertIdSequence(JsonStore store) {
        this.store = store;
    }

    public synchronized long next() {
        long next = store.read(DOCUMENT, Sequence.class)
                .map(Sequence::last)
                .orElse(0L) + 1;
        store.write(DOCUMENT, new Sequence(next));
        return next;
    }
}
