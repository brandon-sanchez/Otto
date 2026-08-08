package otto.directory;

/**
 * A player's availability, ordered from best to worst. Built from the
 * worse of Sleeper's two designation fields: `status` (roster standing)
 * and `injury_status` (the game-week injury report).
 *
 * UNKNOWN covers a designation this code does not recognize yet. It
 * sits between the uncertain and the ruled-out bands, so a drifted new
 * value still surfaces as a Medium-confidence Alert instead of passing
 * as healthy or claiming a certainty the data does not give.
 */
public enum PlayerHealth {
    ACTIVE,
    PROBABLE,
    QUESTIONABLE,
    DOUBTFUL,
    UNKNOWN,
    OUT,
    NOT_ACTIVE,
    DID_NOT_REPORT,
    SUSPENDED,
    PUP,
    IR;

    public static PlayerHealth from(String status, String injuryStatus) {
        PlayerHealth roster = fromStatus(status);
        PlayerHealth injury = fromInjuryStatus(injuryStatus);
        return roster.isWorseThan(injury) ? roster : injury;
    }

    private static PlayerHealth fromStatus(String status) {
        if (status == null || status.isBlank()) {
            return ACTIVE;
        }
        return switch (status) {
            case "Active" -> ACTIVE;
            case "Inactive", "Practice Squad" -> NOT_ACTIVE;
            case "Injured Reserve" -> IR;
            case "Physically Unable to Perform" -> PUP;
            case "Non Football Injury" -> PUP;
            // A drifted roster status alone never flags a player; the
            // injury report is the field that carries game availability.
            default -> ACTIVE;
        };
    }

    private static PlayerHealth fromInjuryStatus(String injuryStatus) {
        if (injuryStatus == null || injuryStatus.isBlank()) {
            return ACTIVE;
        }
        return switch (injuryStatus) {
            case "Probable" -> PROBABLE;
            case "Questionable" -> QUESTIONABLE;
            case "Doubtful" -> DOUBTFUL;
            case "Out", "COV" -> OUT;
            case "NA" -> NOT_ACTIVE;
            case "DNR" -> DID_NOT_REPORT;
            case "Sus" -> SUSPENDED;
            case "PUP" -> PUP;
            case "IR" -> IR;
            default -> UNKNOWN;
        };
    }

    public boolean isWorseThan(PlayerHealth other) {
        return ordinal() > other.ordinal();
    }

    /** True for every designation that means the player cannot play. */
    public boolean rulesOutPlaying() {
        return ordinal() >= OUT.ordinal();
    }
}
