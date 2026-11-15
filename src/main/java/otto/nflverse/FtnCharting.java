package otto.nflverse;

import java.time.Instant;
import java.util.List;

/**
 * FTN's charting of each game: when it was charted, and which read every
 * thrown pass went to. FTN stamps every play with the moment it pulled
 * the game, and re-pulls old games in bulk, so the stamp says when a
 * game's charting was last published and not that anything in it
 * changed.
 *
 * @param assetUpdatedAt the release timestamp this copy was taken at
 * @param checkedAt when the hourly timestamp check last ran
 * @param coverage one unit per charted game, published at its date_pulled
 * @param reads every charted play FTN gave a read code; a play with no
 *        throw is absent. Joined to {@link PlayByPlay} on game and play
 *        id, a read says who the quarterback was looking at.
 */
public record FtnCharting(
        String season,
        Instant assetUpdatedAt,
        Instant checkedAt,
        Coverage coverage,
        List<Read> reads) implements NflverseFeed {

    static final String FIRST_READ = "1";

    /**
     * What the reader keeps of one charted play.
     *
     * @param readThrown FTN's {@code read_thrown} code, or null when blank
     */
    public record Play(String gameId, int week, String playId, Instant datePulled,
            String readThrown) {
    }

    /**
     * Which read one thrown pass went to.
     *
     * @param code FTN's {@code read_thrown}: "1", "2" and so on for the
     *        read the ball went to, "CHK" for a checkdown, "DES" for a
     *        designed throw such as a screen, "SD" for a scramble drill
     */
    public record Read(String gameId, String playId, String code) {

        /**
         * Returns true when the ball went to the quarterback's first read.
         * FTN's data dictionary describes the codes differently; the data
         * itself puts 1 on the first read, and this follows the data.
         */
        public boolean firstRead() {
            return FIRST_READ.equals(code);
        }
    }
}
