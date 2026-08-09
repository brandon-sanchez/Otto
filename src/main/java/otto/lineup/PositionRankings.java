package otto.lineup;

import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.directory.PlayerDirectoryStore;

/**
 * Ranks a week's projection table against the stored Player Directory.
 * Both readers of a rank - the Notable Player rule and replacement
 * level - need the same two documents joined the same way, so the join
 * lives here rather than in each of them.
 */
@Component
public class PositionRankings {

    private final PlayerDirectoryStore directoryStore;

    public PositionRankings(PlayerDirectoryStore directoryStore) {
        this.directoryStore = directoryStore;
    }

    /**
     * The table ranked within each position, or empty when no Player
     * Directory has landed yet and nothing can be told apart by
     * position.
     */
    public Optional<PositionRanking> rank(ProjectionTable projections) {
        return directoryStore.read()
                .map(directory -> PositionRanking.of(projections, directory));
    }
}
