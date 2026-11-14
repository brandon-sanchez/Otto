package otto.directory;

import java.time.Instant;
import java.util.Map;

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
 */
public record PlayerDirectory(
        String etag,
        Instant checkedAt,
        Map<String, DirectoryPlayer> players,
        Map<String, DirectoryPlayer> defenders) {

    public PlayerDirectory {
        defenders = defenders == null ? Map.of() : defenders;
    }

    public PlayerDirectory withCheckedAt(Instant newCheckedAt) {
        return new PlayerDirectory(etag, newCheckedAt, players, defenders);
    }
}
