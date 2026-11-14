package otto.board;

/**
 * One way of choosing a claim round's pickups that the weekly
 * comparison judges: the evidence-based waiver board, or one of the
 * simple baselines it has to beat.
 */
public enum BoardStrategy {

    /** The evidence-based board the model writes and code checks. */
    EVIDENCE_BOARD,

    /** The fixed 100-point score the waiver Alert ranks by. */
    SCORED_BOARD,

    /** Claim nobody, which adds nothing to the lineup by definition. */
    NO_PICKUP,

    /**
     * The best-projected free agents at the user's neediest position
     * first: a position that fills an open slot, then the position of
     * his lowest-ranked slot against the league.
     */
    NEED_FIRST,

    /** The best-projected free agents this week, whatever the roster needs. */
    PLAYER_RANK,

    /**
     * Sleeper's most-added players as published, rostered ones
     * included, so what is judged is a manager following the list as it
     * stands.
     */
    TOP_TRENDING
}
