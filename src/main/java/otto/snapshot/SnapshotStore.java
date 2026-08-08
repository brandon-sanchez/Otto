package otto.snapshot;

import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.storage.JsonStore;

/** Holds the current and previous Snapshot documents. */
@Component
public class SnapshotStore {

    private static final String CURRENT = "snapshot-current";
    private static final String PREVIOUS = "snapshot-previous";

    private final JsonStore store;

    public SnapshotStore(JsonStore store) {
        this.store = store;
    }

    public Optional<Snapshot> current() {
        return store.read(CURRENT, Snapshot.class);
    }

    public Optional<Snapshot> previous() {
        return store.read(PREVIOUS, Snapshot.class);
    }

    /**
     * Stores the new Snapshot, moving the old current one down.
     *
     * @return the Snapshot this one is diffed against
     */
    public Optional<Snapshot> advance(Snapshot snapshot) {
        Optional<Snapshot> old = current();
        old.ifPresent(previous -> store.write(PREVIOUS, previous));
        store.write(CURRENT, snapshot);
        return old;
    }
}
