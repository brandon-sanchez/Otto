package otto.storage;

import java.util.Optional;

/**
 * Where a stored document's bytes live. {@link JsonStore} owns the
 * JSON; a backend owns durability. The split is what lets the laptop
 * keep a directory of files while the deployed assistant keeps an
 * object in S3 and a table in DynamoDB, with no caller changing.
 */
public interface DocumentBackend {

    /** The stored bytes of one document, or empty when it has none yet. */
    Optional<byte[]> load(String name);

    /** Stores the bytes of one document under its name. */
    void store(String name, byte[] json);

    /**
     * Ends one run: a backend that batches writes pushes them here. A
     * backend that writes each document straight through does nothing.
     * The Check calls this once per run, so a minute of writes costs
     * one round trip rather than one per document.
     */
    default void flush() {
    }
}
