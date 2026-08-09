package otto.alerts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JavaType;
import org.springframework.stereotype.Component;

import otto.storage.JsonStore;
import otto.storage.OttoJson;

/**
 * The user's active Mutes. A target is either a class of Alerts
 * ("class:edge", "class:legality") or one player's news
 * ("player:4034"). A Mute silences matching notifications until the
 * user unmutes it; it never cancels a Recommendation.
 */
@Component
public class MuteStore {

    public record Mute(String target, Instant at) {
    }

    private static final String PLAYER = "player:";

    /** The target that silences one player's news. */
    public static String playerTarget(String playerId) {
        return PLAYER + playerId;
    }

    /** The player a target silences, when it silences one at all. */
    public static Optional<String> playerIdOf(String target) {
        return target != null && target.startsWith(PLAYER)
                ? Optional.of(target.substring(PLAYER.length()))
                : Optional.empty();
    }

    private static final String DOCUMENT = "mutes";
    private static final JavaType LIST_TYPE =
            OttoJson.MAPPER.getTypeFactory().constructCollectionType(List.class, Mute.class);

    private final JsonStore store;

    public MuteStore(JsonStore store) {
        this.store = store;
    }

    public List<Mute> all() {
        return store.<List<Mute>>read(DOCUMENT, LIST_TYPE).orElseGet(List::of);
    }

    public boolean muted(String target) {
        return all().stream().anyMatch(mute -> mute.target().equals(target));
    }

    public synchronized void mute(String target, Instant at) {
        if (muted(target)) {
            return;
        }
        List<Mute> mutes = new ArrayList<>(all());
        mutes.add(new Mute(target, at));
        store.write(DOCUMENT, mutes);
    }

    public synchronized void unmute(String target) {
        List<Mute> mutes = all().stream()
                .filter(mute -> !mute.target().equals(target))
                .toList();
        store.write(DOCUMENT, mutes);
    }
}
