package otto.team;

import java.util.Optional;

import otto.sleeper.SleeperAdapter;

/**
 * One team's direction and the plain-words reason for it.
 *
 * @param manager the team's manager, as the league names him
 * @param direction the direction Otto treats the team as having: the
 *        user's own choice when he made one, and never rebuilding for
 *        the user in a dynasty league until he says so
 * @param measured the direction the team's market value, record and
 *        points point to
 * @param reason one line on why, such as "8-2 with the most points and
 *        the 3rd-most valuable roster"
 * @param pointsRank 1 for the most points scored in the league
 * @param marketValue the summed market value of every player on the
 *        roster, IR and taxi included; empty when no market list could
 *        be read
 * @param valueRank 1 for the most valuable roster; empty with
 *        {@code marketValue}
 */
public record TeamDirection(
        int rosterId,
        String manager,
        boolean userTeam,
        Direction direction,
        Direction measured,
        Basis basis,
        String reason,
        SleeperAdapter.TeamRecord record,
        int pointsRank,
        Optional<Integer> marketValue,
        Optional<Integer> valueRank) {

    /** Where the user's direction comes from. Every other team's is {@link #MEASURED}. */
    public enum Basis {
        /** Measured from the team's value, record and points. */
        MEASURED,
        /** The user set it in chat. */
        CHOSEN,
        /**
         * The user's team measures as rebuilding in a dynasty league, and
         * he has not said so, so it is held in the middle until he does.
         */
        AWAITING_CONFIRMATION
    }
}
