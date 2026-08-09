package otto.events;

public enum EventType {
    PLAYER_STATUS_CHANGE,
    SNAPSHOT_DIFF,
    /** A Watchlist move no Snapshot carries: a national trend, a projection. */
    WATCHLIST_MOVE,
    SOURCE_UNAVAILABLE,
    ALERT_SENT,
    USER_ACTION,
    VERIFIED,
    NOTE_SENT
}
