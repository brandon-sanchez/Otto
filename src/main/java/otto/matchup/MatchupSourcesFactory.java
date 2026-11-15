package otto.matchup;

import org.springframework.stereotype.Component;

import otto.storage.JsonStore;

/** Opens {@link MatchupSources} over a fresh read-only copy of the store. */
@Component
public class MatchupSourcesFactory {

    private final JsonStore documents;

    public MatchupSourcesFactory(JsonStore documents) {
        this.documents = documents;
    }

    /**
     * Returns the matchup sources as stored now. Each document is read
     * from storage at most once, the first time it is asked for, and
     * nothing is written.
     */
    public MatchupSources open() {
        return new MatchupSources(documents.snapshot());
    }
}
