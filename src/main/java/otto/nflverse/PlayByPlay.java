package otto.nflverse;

import java.time.Instant;
import java.util.List;

/**
 * Every regular-season target and carry in nflverse's play-by-play, one
 * row per play and player. Two-point tries, kneels, spikes and plays a
 * penalty wiped out are not opportunities and are left out.
 *
 * @param priorSeasonFinal true when the current season has no played
 *        week yet, so these rows are last season's final record
 */
public record PlayByPlay(
        String season,
        boolean priorSeasonFinal,
        Instant assetUpdatedAt,
        Instant checkedAt,
        Coverage coverage,
        List<Opportunity> rows) implements NflverseFeed {

    /** What a player was given on one play. */
    public enum Kind {
        TARGET,
        CARRY
    }

    /** The yard line, measured from the goal, inside which a play is in the red zone. */
    public static final int RED_ZONE = 20;

    /**
     * One player's target or carry on one play.
     *
     * @param playId nflverse's play id, unique within a game and shared
     *        with FTN's charting of the same play
     * @param team the offence, in Sleeper's codes
     * @param defense the defence, in Sleeper's codes
     * @param gsisId the targeted receiver or the ball carrier
     * @param yardline100 yards from the goal line at the snap, 1 to 99
     * @param airYards how far past the line the ball travelled, or null
     *        on a carry or when the file left it out
     */
    public record Opportunity(String gameId, String playId, int week, String team,
            String defense, Kind kind, String gsisId, int yardline100, Integer airYards) {

        /** Returns true when the play started at or inside the opponent's 20. */
        public boolean redZone() {
            return yardline100 <= RED_ZONE;
        }

        /** Returns true for a target thrown at least as far as the goal line. */
        public boolean endZoneTarget() {
            return kind == Kind.TARGET && airYards != null && airYards >= yardline100;
        }
    }
}
