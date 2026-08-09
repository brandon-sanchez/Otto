package otto.nflverse;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * The DynastyProcess player-id mapping, trimmed to the one join this
 * system needs: a Sleeper player id to the nflverse gsis id. Sleeper
 * names every player its own way and nflverse another, so the id is the
 * only safe key - the published mapping carries two different players
 * called Justin Jefferson.
 *
 * @param etag the ETag of the stored copy, for the conditional GET
 * @param checkedAt when the mapping was last checked
 */
public record PlayerIdMap(String etag, Instant checkedAt, Map<String, String> sleeperToGsis) {

    public Optional<String> gsisFor(String sleeperId) {
        return Optional.ofNullable(sleeperToGsis.get(sleeperId));
    }

    public PlayerIdMap withCheckedAt(Instant newCheckedAt) {
        return new PlayerIdMap(etag, newCheckedAt, sleeperToGsis);
    }
}
