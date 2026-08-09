package otto.settings;

import java.util.function.UnaryOperator;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.lineup.PositionCutoffs;
import otto.storage.JsonStore;

/**
 * The stored Settings document. Nothing is written until the user
 * changes something, so the configured defaults keep working on a fresh
 * install and a change made by chat outlives every Check and restart.
 */
@Component
public class SettingsStore {

    private static final String DOCUMENT = "settings";

    private final JsonStore store;
    private final double configuredEdgeThreshold;
    private final PositionCutoffs configuredNotableCutoffs;

    public SettingsStore(JsonStore store, OttoProperties properties) {
        this.store = store;
        this.configuredEdgeThreshold = properties.edgeThreshold();
        this.configuredNotableCutoffs = properties.notableCutoffs();
    }

    public Settings current() {
        return store.read(DOCUMENT, Settings.class)
                .orElseGet(() -> Settings.defaults(
                        configuredEdgeThreshold, configuredNotableCutoffs));
    }

    /**
     * The ranks that make a dropped player league news. Configuration
     * supplies the default and the chat overrides it, so one number
     * answers wherever it is read.
     */
    public PositionCutoffs notableCutoffs() {
        return current().notablePlayerCutoffs();
    }

    public boolean enabled(Trigger trigger) {
        return current().enabled(trigger);
    }

    public double edgeThreshold() {
        return current().edgeThreshold();
    }

    /** Applies one change and stores the result. */
    public synchronized Settings change(UnaryOperator<Settings> change) {
        Settings changed = change.apply(current());
        store.write(DOCUMENT, changed);
        return changed;
    }
}
