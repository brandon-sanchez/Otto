package otto.directory;

import java.time.Instant;
import java.util.Map;

/**
 * The stored, trimmed subset of Sleeper's players file: QB, RB, WR,
 * and TE only. All player lookups read from it.
 */
public record PlayerDirectory(
        String etag,
        Instant checkedAt,
        Map<String, DirectoryPlayer> players) {

    public PlayerDirectory withCheckedAt(Instant newCheckedAt) {
        return new PlayerDirectory(etag, newCheckedAt, players);
    }
}
