package otto.sleeper;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * In-memory ETag cache for Sleeper conditional GETs: the last ETag and
 * body per path, so a 304 answer rebuilds from the cached body.
 */
@Component
public class SleeperCache {

    public record Entry(String etag, JsonNode body) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public Optional<Entry> get(String path) {
        return Optional.ofNullable(entries.get(path));
    }

    public void put(String path, String etag, JsonNode body) {
        entries.put(path, new Entry(etag, body));
    }

    public void clear() {
        entries.clear();
    }
}
