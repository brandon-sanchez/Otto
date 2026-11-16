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
    /** One player's usage and advanced stats for a week, recent weeks or the season, in chat. */
    PLAYER_STATS,
    ALERT,
    /** The answer to the user asking about his management habits. */
    HABITS,
    /** A chat answer about what one player or draft pick is worth on the trade market. */
    MARKET_VALUE,
    /** The user's team read position by position, when he asks about its weaknesses. */
    TEAM_WEAKNESSES,
    /** A chat answer about buy-low and sell-high players. */
    BUY_LOW_SELL_HIGH,
    /** Every team in the league labelled contending, in the middle or rebuilding. */
    TEAM_DIRECTIONS,
    /** Named trade offers, suggested in chat when the user asks. */
    TRADE_IDEAS
}
