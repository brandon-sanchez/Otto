package otto.aws;

import java.util.function.Supplier;

import otto.storage.JsonStore;

/**
 * One invocation's work, and the store call that keeps what it learned.
 * A run that failed part way still stores - a Check that read the
 * Snapshot and then lost a feed has learned the Snapshot. A store that
 * fails as well never hides why the run failed: it is attached to the
 * first failure rather than thrown over it.
 */
final class StoredRun {

    private StoredRun() {
    }

    static <T> T storing(JsonStore store, Supplier<T> work) {
        T outcome;
        try {
            outcome = work.get();
        } catch (RuntimeException failure) {
            try {
                store.flush();
            } catch (RuntimeException alsoFailed) {
                failure.addSuppressed(alsoFailed);
            }
            throw failure;
        }
        store.flush();
        return outcome;
    }
}
