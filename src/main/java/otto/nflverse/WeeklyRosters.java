package otto.nflverse;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The stored, trimmed nflverse weekly rosters: one row per player per
 * week, carrying the league's own roster-standing code. It answers one
 * question the Sleeper Player Directory cannot - does the man ahead
 * have a date he comes back on?
 *
 * <p>Sleeper publishes a single "Injured Reserve" status. The modern IR
 * rule splits that in two: a player placed on IR and designated for
 * return misses four games and then plays, and a player placed on IR
 * with no designation is gone. Those are opposite facts for a waiver
 * bid, so the breakout tag reads the code rather than the label.
 *
 * @param assetUpdatedAt the release timestamp this copy was taken at
 * @param checkedAt when the hourly timestamp check last ran
 */
public record WeeklyRosters(
        String season,
        Instant assetUpdatedAt,
        Instant checkedAt,
        List<Standing> rows) implements NflverseFeed {

    /**
     * The roster-standing codes that carry no reversion date. Every
     * other code - including IR designated for return, PUP, the
     * non-football lists and a suspension - names an absence the player
     * comes back from, so it is a stream and not a breakout.
     *
     * <p>The codes are the NFL's own and are not published with a key,
     * so each one here was read off the feed itself: over the 2024
     * season, a player on R01 is active again in a later week 18% of
     * the time and a player on R02 4% of the time, against 75% for R48,
     * the IR code that carries a return designation, and 59% for R04,
     * the PUP code. A code this set does not name is treated as an
     * absence he returns from, which is the reading that never pays a
     * breakout price for a man who comes back.
     */
    private static final Set<String> SEASON_ENDING = Set.of(
            // Reserve/Injured, no return designation.
            "R01",
            // Reserve/Retired.
            "R02");

    /** One player's roster standing in one week. */
    public record Standing(String gsisId, int week, String code) {
    }

    /** What a player's newest standing says about his coming back. */
    public enum Outlook {

        /** No reversion date exists: his season is over. */
        SEASON_ENDING,

        /**
         * His season is not over. Either he is playing, or he is on a
         * list he comes back from on a stated date.
         */
        RETURNING,

        /**
         * The feed does not say. Either it was never downloaded or it
         * has no row for this player, and the two are the same answer:
         * nothing may be claimed either way.
         */
        UNKNOWN
    }

    /**
     * Every player's outlook, in one pass. Callers hold the result for
     * the length of their own run rather than asking per player: this
     * file carries a row per player per week, so a scan per lookup
     * would read tens of thousands of rows to answer one question.
     */
    public Map<String, Outlook> outlooks() {
        Map<String, Standing> newest = new HashMap<>();
        rows.forEach(standing -> newest.merge(standing.gsisId(), standing,
                (held, arriving) -> arriving.week() > held.week() ? arriving : held));
        Map<String, Outlook> outlooks = new HashMap<>();
        newest.forEach((gsisId, standing) -> outlooks.put(gsisId,
                SEASON_ENDING.contains(standing.code())
                        ? Outlook.SEASON_ENDING
                        : Outlook.RETURNING));
        return Map.copyOf(outlooks);
    }

    public WeeklyRosters withCheckedAt(Instant newCheckedAt) {
        return new WeeklyRosters(season, assetUpdatedAt, newCheckedAt, rows);
    }
}
