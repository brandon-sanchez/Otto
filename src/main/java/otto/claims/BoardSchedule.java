package otto.claims;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import otto.sleeper.LeagueRules;

/**
 * When a claim round's waiver board goes out, and the key its Alert is
 * sent under. Both waiver boards and the role-slip advice held for them
 * read the round's timing here, so none of them depends on another.
 */
public final class BoardSchedule {

    /** The user's zone. Pinned here, not read from the clock: the Check runs in UTC. */
    private static final ZoneId BOARD_ZONE = ZoneId.of("America/Los_Angeles");

    private static final LocalTime BOARD_TIME = LocalTime.of(18, 0);

    /**
     * How long before the claim deadline the evidence-based board goes
     * out even though the latest week's data is late.
     */
    public static final Duration FALLBACK_BUFFER = Duration.ofHours(3);

    private BoardSchedule() {
    }

    /**
     * Returns when the waiver board for this claim deadline is due:
     * 18:00 America/Los_Angeles on the calendar day before it, read in
     * the zone Sleeper runs claims in.
     */
    public static ZonedDateTime due(ZonedDateTime deadline) {
        return deadline.withZoneSameInstant(LeagueRules.CLAIM_ZONE).toLocalDate().minusDays(1)
                .atTime(BOARD_TIME)
                .atZone(BOARD_ZONE);
    }

    /**
     * Returns the latest a board for this claim deadline goes out: a
     * board waiting for late data is built at
     * {@link #FALLBACK_BUFFER} before the deadline at the latest.
     */
    public static Instant latest(ZonedDateTime deadline) {
        return deadline.minus(FALLBACK_BUFFER).toInstant();
    }

    /** Returns the Alert key of the waiver board for this claim deadline. */
    public static String alertKey(ZonedDateTime deadline) {
        return "alert:waiver:" + due(deadline).toLocalDate();
    }
}
