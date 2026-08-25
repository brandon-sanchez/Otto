package otto.alerts;

import java.util.Map;

import otto.storage.JsonStore;

/** Fault states a process-boundary scenario can arrange in external storage. */
public final class OutboxFaults {

    private OutboxFaults() {
    }

    public static void pointKeyAtMissingDelivery(JsonStore store, String key) {
        store.write(AlertDeliveryOutbox.keyDocument(key), Map.of("alertId", 999));
    }
}
