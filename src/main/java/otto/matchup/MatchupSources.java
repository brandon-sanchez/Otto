package otto.matchup;

import otto.directory.PlayerDirectoryStore;
import otto.nflverse.NflverseStore;
import otto.storage.JsonStore;

/**
 * Every matchup source read from one copy of the store: the stored
 * nflverse documents and the player directory, and the models derived
 * from them. One piece of work reads each document once, and every fact
 * it builds agrees with the same copy of each source.
 *
 * <p>Open one per piece of work with {@link MatchupSourcesFactory#open()}
 * and drop it after. An instance is not safe for use by more than one
 * thread at a time.
 */
public final class MatchupSources {

    private final NflverseStore store;
    private final PlayerDirectoryStore directory;
    private final DefensiveStarters starters;

    MatchupSources(JsonStore snapshot) {
        this.store = new NflverseStore(snapshot);
        this.directory = new PlayerDirectoryStore(snapshot);
        this.starters = new DefensiveStarters(store, directory);
    }

    /** Returns the stored nflverse documents, read through this copy. */
    public NflverseStore store() {
        return store;
    }

    /** Returns each defense's starters and their designations. */
    public DefensiveStarters starters() {
        return starters;
    }
}
