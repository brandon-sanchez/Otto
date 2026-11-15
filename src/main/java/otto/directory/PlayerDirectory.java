package otto.directory;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * The stored, trimmed subset of Sleeper's players file. Its players are
 * QB, RB, WR, and TE only, and all player lookups read from them.
 *
 * Defenders on an NFL team ride alongside in their own map, kept only
 * for the injury designation that says whether a defensive starter
 * plays. No lookup finds them and no roster check reads them.
 *
 * @param players Sleeper id to offensive player
 * @param defenders Sleeper id to defender; empty when the stored copy
 *        carries none
 * @param defendersKept true when this copy was trimmed from a players
 *        file with its defenders kept; false for a copy that never
 *        kept them, which must be downloaded in full once however its
 *        ETag reads
 */
public record PlayerDirectory(
        String etag,
        Instant checkedAt,
        Map<String, DirectoryPlayer> players,
        Map<String, DirectoryPlayer> defenders,
        boolean defendersKept) {

    public PlayerDirectory {
        defenders = defenders == null ? Map.of() : defenders;
    }

    /** A copy trimmed from a players file with its defenders kept. */
    public PlayerDirectory(String etag, Instant checkedAt, Map<String, DirectoryPlayer> players,
            Map<String, DirectoryPlayer> defenders) {
        this(etag, checkedAt, players, defenders, true);
    }

    /**
     * Returns the ETag to revalidate this copy with; empty for a copy
     * that never kept defenders, since Sleeper would answer 304 and the
     * copy would stay without them until the file itself changed.
     */
    public Optional<String> revalidationEtag() {
        return defendersKept ? Optional.ofNullable(etag) : Optional.empty();
    }

    public PlayerDirectory withCheckedAt(Instant newCheckedAt) {
        return new PlayerDirectory(etag, newCheckedAt, players, defenders, defendersKept);
    }
}
