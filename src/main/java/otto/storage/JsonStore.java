package otto.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;

import com.fasterxml.jackson.databind.JavaType;
import org.springframework.stereotype.Component;

/**
 * Document storage: one JSON document per name. This class is the
 * storage seam every caller holds. It turns a document into JSON and
 * back and hands the bytes to a {@link DocumentBackend}, so where the
 * bytes live - a directory of files, an object in S3, a row in
 * DynamoDB - is settled in one place.
 */
@Component
public class JsonStore {

    private final DocumentBackend backend;

    public JsonStore(DocumentBackend backend) {
        this.backend = backend;
    }

    public <T> Optional<T> read(String name, Class<T> type) {
        return read(name, OttoJson.MAPPER.constructType(type));
    }

    public <T> Optional<T> read(String name, JavaType type) {
        Optional<byte[]> stored = backend.load(name);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OttoJson.MAPPER.readValue(stored.get(), type));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read stored document " + name, e);
        }
    }

    public void write(String name, Object value) {
        try {
            backend.store(name,
                    OttoJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write stored document " + name, e);
        }
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
}
