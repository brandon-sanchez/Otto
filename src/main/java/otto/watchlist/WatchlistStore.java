package otto.watchlist;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JavaType;
import org.springframework.stereotype.Component;

import otto.storage.JsonStore;
import otto.storage.OttoJson;

/**
 * The user's Watchlist: the players Otto watches for drops, sharp
 * national trends, projection updates and Snipes, whoever rosters them.
 * Sleeper's API exposes no such list, so this document is the only one
 * there is - it is built by chat and it outlives every Check.
 *
 * The name, position and team are stored beside the id because a
 * watched player need not be on any roster, so no Snapshot can be
 * relied on to name him.
 */
@Component
public class WatchlistStore {

    public record Entry(String playerId, String player, String position, String team,
            Instant at) {
    }

    private static final String DOCUMENT = "watchlist";
    private static final JavaType LIST_TYPE =
            OttoJson.MAPPER.getTypeFactory().constructCollectionType(List.class, Entry.class);

    private final JsonStore store;

    public WatchlistStore(JsonStore store) {
        this.store = store;
    }

    public List<Entry> all() {
        return store.<List<Entry>>read(DOCUMENT, LIST_TYPE).orElseGet(List::of);
    }

    public Set<String> playerIds() {
        return all().stream()
                .map(Entry::playerId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public boolean contains(String playerId) {
        return playerId != null && all().stream()
                .anyMatch(entry -> entry.playerId().equals(playerId));
    }

    /**
     * Adds the player unless he is already watched.
     *
     * @return true when added; false when he was already on the list
     */
    public synchronized boolean add(Entry entry) {
        if (contains(entry.playerId())) {
            return false;
        }
        List<Entry> entries = new ArrayList<>(all());
        entries.add(entry);
        store.write(DOCUMENT, entries);
        return true;
    }

    /**
     * Removes the player.
     *
     * @return true when he was on the list
     */
    public synchronized boolean remove(String playerId) {
        List<Entry> entries = all();
        List<Entry> kept = entries.stream()
                .filter(entry -> !entry.playerId().equals(playerId))
                .toList();
        if (kept.size() == entries.size()) {
            return false;
        }
        store.write(DOCUMENT, kept);
        return true;
    }
}
