package otto.alerts;

import org.springframework.stereotype.Component;

import otto.storage.JsonStore;

/**
 * Hands out the short ids the inline buttons reference. The counter
 * persists before the outbox writes pending delivery state and before
 * the send. A stale pending delivery reuses its recorded id; only a
 * failure before pending state is stored can leave an unused gap.
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
