package otto.habits;

import java.time.Instant;
import java.util.Optional;

/**
 * One start/sit or waiver call Otto sent the user, as the weekly capture
 * reads it from the Event Log and the Decision Records.
 *
 * @param key identifies the call, so it is stored once however often it
 *        is read
 * @param week the week the call was about; for a waiver call, the week
 *        the capture first saw it in
 * @param playerId the player Otto said to start or to claim
 * @param over the player Otto said to sit for him, or to drop for him;
 *        empty for a claim into an open roster spot
 */
public record AdviceCall(String key, Kind kind, int week, Instant at, String playerId,
        Optional<String> over) {

    public AdviceCall {
        over = over == null ? Optional.empty() : over;
    }

    /** What Otto said to do. */
    public enum Kind {
        /** Start {@code playerId} over {@code over}. */
        START,
        /** Claim {@code playerId}, dropping {@code over} when present. */
        CLAIM
    }
}
