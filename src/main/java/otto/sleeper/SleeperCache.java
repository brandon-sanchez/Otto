package otto.sleeper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * In-memory ETag cache for Sleeper conditional GETs: the last ETag and
 * body per path, so a 304 answer rebuilds from the cached body.
 *
 * Each entry also remembers when it arrived, which is what lets a
 * reader decide the stored copy is still current enough to answer from
 * without a request at all.
 */
@Component
public class SleeperCache {

    public record Entry(String etag, JsonNode body, Instant fetchedAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public Optional<Entry> get(String path) {
        return Optional.ofNullable(entries.get(path));
    }

    /** The stored body, but only while it is younger than maxAge. */
    public Optional<JsonNode> fresh(String path, Duration maxAge, Instant now) {
        return get(path)
                .filter(entry -> Duration.between(entry.fetchedAt(), now).compareTo(maxAge) < 0)
                .map(Entry::body);
    }

    public void put(String path, String etag, JsonNode body, Instant fetchedAt) {
        entries.put(path, new Entry(etag, body, fetchedAt));
    }

    public void clear() {
        entries.clear();
    }
}
