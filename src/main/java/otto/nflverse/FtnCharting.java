package otto.nflverse;

import java.time.Instant;
import java.util.List;

/**
 * When FTN charted each game, held before any charting column is read.
 * FTN stamps every play with the moment it pulled the game, and re-pulls
 * old games in bulk, so the stamp says when a game's charting was last
 * published and not that anything in it changed. The coverage keeps one
 * unit per game with that stamp; the plays themselves are not stored.
 *
 * @param assetUpdatedAt the release timestamp this copy was taken at
 * @param checkedAt when the hourly timestamp check last ran
 * @param coverage one unit per charted game, published at its date_pulled
 */
public record FtnCharting(
        String season,
        Instant assetUpdatedAt,
        Instant checkedAt,
        Coverage coverage) implements NflverseFeed<FtnCharting.Play> {

    /** What the reader keeps of one charted play. */
    public record Play(String gameId, int week, String playId, Instant datePulled) {
    }

    @Override
    public List<Play> rows() {
        return List.of();
    }
}
