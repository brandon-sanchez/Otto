package otto.check;

import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.storage.JsonStore;

@Component
public class LastCheckStore {

    private static final String DOCUMENT = "last-check";

    private final JsonStore store;

    public LastCheckStore(JsonStore store) {
        this.store = store;
    }

    public Optional<LastCheck> read() {
        return store.read(DOCUMENT, LastCheck.class);
    }

    public void write(LastCheck state) {
        store.write(DOCUMENT, state);
    }
}
