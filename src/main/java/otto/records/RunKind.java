package otto.records;

/** Which kind of recommendation a run produced. */
public enum RunKind {
    WAIVER_BOARD,
    /**
     * The boards the waiver board is judged against, the scored board
     * among them, built for the same claim round and never sent.
     */
    WAIVER_BASELINES,
    PLAYER_DECISION,
    /** A player's ceiling and floor, explained in chat when the user asks. */
    PLAYER_CEILING,
    ALERT
}
