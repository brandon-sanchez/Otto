package otto.storage;

import java.time.Instant;
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

    /**
     * One document's stored bytes and the version they were read at.
     *
     * @param version changes whenever the bytes do, so a caller that
     *        parsed the bytes of this version may reuse what it parsed;
     *        null when the backend keeps no version, and the bytes must
     *        be read as new
     */
    record Versioned(byte[] bytes, String version) {
    }

    /**
     * The stored bytes of one document with their version, or empty when
     * it has none yet. A backend that keeps no versions reports none.
     */
    default Optional<Versioned> loadVersioned(String name) {
        return load(name).map(bytes -> new Versioned(bytes, null));
    }

    /** Stores the bytes of one document under its name. */
    void store(String name, byte[] json);

    /**
     * Stores the lease under its name unless another holder's lease on
     * it is still live at {@code now}. Taking a lease the holder already
     * has extends it. A backend that several processes share decides
     * this in one atomic step; this default is atomic only within one
     * process, which is all a backend on one machine needs.
     *
     * @return false when another holder's lease is still live
     */
    default boolean lease(String name, Lease lease, Instant now) {
        synchronized (this) {
            if (load(name).map(Lease::parse)
                    .filter(held -> held.keepsOut(lease.holder(), now))
                    .isPresent()) {
                return false;
            }
            store(name, lease.bytes());
            return true;
        }
    }

    /**
     * Ends the holder's lease on {@code name}. A lease that has passed
     * to another holder is left alone.
     */
    default void release(String name, String holder) {
        synchronized (this) {
            if (load(name).map(Lease::parse)
                    .filter(held -> held.holder().equals(holder))
                    .isPresent()) {
                store(name, new Lease(holder, Instant.EPOCH).bytes());
            }
        }
    }

    /**
     * Ends one run: a backend that batches writes pushes them here. A
     * backend that writes each document straight through does nothing.
     * The Check calls this once per run, so a minute of writes costs
     * one round trip rather than one per document.
     */
    default void flush() {
    }
}
