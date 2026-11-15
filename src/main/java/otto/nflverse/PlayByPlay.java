package otto.nflverse;

import java.time.Instant;
import java.util.List;

/**
 * nflverse's regular-season play-by-play, trimmed to two kinds of row:
 * every target and carry, one row per play and player, and every
 * dropback, one row per pass play or scramble. Two-point tries, kneels, spikes and
 * plays a penalty wiped out are left out.
 *
 * @param priorSeasonFinal true when the current season has no played
 *        week yet, so these rows are last season's final record
 * @param rows every target and carry
 * @param dropbacks every pass play, targeted or not, sacks included, and
 *        every scramble
 */
public record PlayByPlay(
        String season,
        boolean priorSeasonFinal,
        Instant assetUpdatedAt,
        Instant checkedAt,
        Coverage coverage,
        List<Opportunity> rows,
        List<Dropback> dropbacks) implements NflverseFeed {

    /** The yard line, measured from the goal, inside which a play is in the red zone. */
    public static final int RED_ZONE = 20;

    public PlayByPlay {
        rows = List.copyOf(rows);
        dropbacks = dropbacks == null ? List.of() : List.copyOf(dropbacks);
    }

    /**
     * One row of the play-by-play as the feed reads it.
     *
     * @param playId nflverse's play id, unique within a game and shared
     *        with FTN's charting of the same play
     */
    public sealed interface Play permits Opportunity, Dropback {

        String gameId();

        String playId();

        int week();
    }

    /** What a player was given on one play. */
    public enum Kind {
        TARGET,
        CARRY
    }

    /**
     * One player's target or carry on one play.
     *
     * @param team the offence, in Sleeper's codes
     * @param defense the defence, in Sleeper's codes
     * @param gsisId the targeted receiver or the ball carrier
     * @param yardline100 yards from the goal line at the snap, 1 to 99
     * @param airYards how far past the line the ball travelled, or null
     *        on a carry or when the file left it out
     */
    public record Opportunity(String gameId, String playId, int week, String team,
            String defense, Kind kind, String gsisId, int yardline100, Integer airYards)
            implements Play {

        /** Returns true when the play started at or inside the opponent's 20. */
        public boolean redZone() {
            return yardline100 <= RED_ZONE;
        }

        /** Returns true for a target thrown at least as far as the goal line. */
        public boolean endZoneTarget() {
            return kind == Kind.TARGET && airYards != null && airYards >= yardline100;
        }
    }

    /**
     * One play on which the quarterback dropped back: a pass attempt,
     * targeted or not, a sack, or a scramble. The play-by-play writes a
     * scramble as a run, and FTN charts its pass rush like any dropback.
     *
     * @param offense the offence, in Sleeper's codes
     * @param defense the defence, in Sleeper's codes
     * @param passerId the quarterback's gsis id, or null when the file names none
     * @param scramble true when he ran instead of throwing or being sacked
     */
    public record Dropback(String gameId, String playId, int week, String offense,
            String defense, String passerId, boolean scramble) implements Play {
    }
}
