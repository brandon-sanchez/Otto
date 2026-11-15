package otto.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JavaType;
import org.springframework.stereotype.Component;

/**
 * Document storage: one JSON document per name. This class is the
 * storage seam every caller holds. It turns a document into JSON and
 * back and hands the bytes to a {@link DocumentBackend}, so where the
 * bytes live - a directory of files, an object in S3, a row in
 * DynamoDB - is settled in one place.
 *
 * <p>A document whose backend reports a version is parsed once per
 * version: reading it again at the same version returns the value
 * parsed before. Stored documents are immutable values, so sharing one
 * parsed copy between readers is safe.
 */
@Component
public class JsonStore {

    private final DocumentBackend backend;

    /** The last value parsed for each versioned document, by name. */
    private final Map<String, Parsed> parsed = new ConcurrentHashMap<>();

    private record Parsed(String version, JavaType type, Object value) {
    }

    public JsonStore(DocumentBackend backend) {
        this.backend = backend;
    }

    public <T> Optional<T> read(String name, Class<T> type) {
        return read(name, OttoJson.MAPPER.constructType(type));
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> read(String name, JavaType type) {
        Optional<DocumentBackend.Versioned> stored = backend.loadVersioned(name);
        if (stored.isEmpty()) {
            parsed.remove(name);
            return Optional.empty();
        }
        String version = stored.get().version();
        Parsed last = parsed.get(name);
        if (version != null && last != null && version.equals(last.version())
                && type.equals(last.type())) {
            return Optional.of((T) last.value());
        }
        try {
            T value = OttoJson.MAPPER.readValue(stored.get().bytes(), type);
            if (version == null) {
                parsed.remove(name);
            } else {
                parsed.put(name, new Parsed(version, type, value));
            }
            return Optional.of(value);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read stored document " + name, e);
        }
    }

    public void write(String name, Object value) {
        parsed.remove(name);
        try {
            backend.store(name,
                    OttoJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write stored document " + name, e);
        }
    }

    /**
     * Takes the named lease, see {@link DocumentBackend#lease}.
     *
     * @return false when another holder's lease is still live
     */
    public boolean lease(String name, Lease lease, Instant now) {
        parsed.remove(name);
        return backend.lease(name, lease, now);
    }

    /** Ends the holder's lease on {@code name}, see {@link DocumentBackend#release}. */
    public void release(String name, String holder) {
        parsed.remove(name);
        backend.release(name, holder);
    }

    /**
     * Ends one run, so a backend that batches writes pushes them. The
     * deployed entry points call this once their work is done, failed
     * or not: a Check that half finished still stored what it learned.
     * The local drivers do not, and need not - a laptop's backend
     * stores each document as it comes rather than a run's worth at a
     * time, so there is nothing held back to push.
     */
    public void flush() {
        backend.flush();
    }

    /**
     * Returns a read-only view that reads each document from this store
     * at most once and hands back the same object on every later read.
     * It suits work that reads the same large documents many times over
     * a few seconds, such as building one piece of advice, and that must
     * see one consistent copy of each. Drop it once that work is done,
     * because it never sees a later write.
     *
     * <p>The view is not safe for use by more than one thread at a time.
     * Its {@link #write} and {@link #flush} throw
     * {@link UnsupportedOperationException}: a view holds no writes, and
     * flushing through it would push the real store's batched writes
     * from the middle of someone else's run.
     */
    public JsonStore snapshot() {
        return new Snapshot(this);
    }

    private static final class Snapshot extends JsonStore {

        private record Key(String name, JavaType type) {
        }

        private final JsonStore source;

        private final Map<Key, Optional<?>> read = new HashMap<>();

        Snapshot(JsonStore source) {
            super(source.backend);
            this.source = source;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> read(String name, JavaType type) {
            return (Optional<T>) read.computeIfAbsent(new Key(name, type),
                    key -> source.read(name, type));
        }

        @Override
        public void write(String name, Object value) {
            throw new UnsupportedOperationException("A snapshot is read-only: " + name);
        }

        @Override
        public void flush() {
            throw new UnsupportedOperationException("A snapshot holds no writes to flush");
        }

        @Override
        public JsonStore snapshot() {
            return this;
        }
    }
}
