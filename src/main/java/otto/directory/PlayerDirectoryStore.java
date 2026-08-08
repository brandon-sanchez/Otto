package otto.directory;

import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.storage.JsonStore;

@Component
public class PlayerDirectoryStore {

    private static final String DOCUMENT = "player-directory";

    private final JsonStore store;

    public PlayerDirectoryStore(JsonStore store) {
        this.store = store;
    }

    public Optional<PlayerDirectory> read() {
        return store.read(DOCUMENT, PlayerDirectory.class);
    }

    public void write(PlayerDirectory directory) {
        store.write(DOCUMENT, directory);
    }
}
