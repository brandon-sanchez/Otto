package otto.board;

import java.time.Instant;
import java.util.List;

/**
 * One instruction the user gave by chat for one league's waiver board,
 * scoped to one claim round: it steers only the board for the claims
 * that close at {@link #claimDeadline()} and ends when they close.
 *
 * @param claimDeadline when the claim round it covers closes
 * @param positions the positions it names, in QB, RB, WR, TE order
 * @param mode whether it ranks those positions first or keeps the
 *        board to them
 * @param setAt when the user set it
 */
public record WaiverPreference(
        String leagueId,
        Instant claimDeadline,
        List<String> positions,
        Mode mode,
        Instant setAt) {

    public WaiverPreference {
        positions = List.copyOf(positions);
    }

    /** How a preference shapes the board. */
    public enum Mode {
        /**
         * Rank the named positions first, while a clearly better pickup
         * elsewhere still makes the board with the reason he beats them.
         */
        FOCUS,
        /** Screen only the named positions. */
        ONLY
    }

    /** Returns the preference as a short phrase, such as "focus on TE". */
    public String phrase() {
        String named = String.join(" and ", positions);
        return switch (mode) {
            case FOCUS -> "focus on " + named;
            case ONLY -> "only " + named;
        };
    }
}
