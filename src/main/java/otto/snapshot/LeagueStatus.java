package otto.snapshot;

/** The league lifecycle state Sleeper reports. */
public enum LeagueStatus {
    PRE_DRAFT, DRAFTING, IN_SEASON, COMPLETE, UNKNOWN;

    public static LeagueStatus fromSleeper(String status) {
        if (status == null) {
            return UNKNOWN;
        }
        return switch (status) {
            case "pre_draft" -> PRE_DRAFT;
            case "drafting" -> DRAFTING;
            case "in_season" -> IN_SEASON;
            case "complete" -> COMPLETE;
            default -> UNKNOWN;
        };
    }
}
