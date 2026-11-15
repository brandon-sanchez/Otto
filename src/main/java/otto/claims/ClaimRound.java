package otto.claims;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import otto.sleeper.LeagueRules;
import otto.sleeper.SleeperAdapter;

/**
 * The league's next claim round as one Check sees it: the claim
 * deadline, when the round's waiver board is due, and the key its Alert
 * is sent under. The waiver board and the role-slip advice held for it
 * read the round here, so neither depends on the other.
 *
 * @param leagueId the Sleeper league the round belongs to
 * @param deadline when the league's claims run, in the zone Sleeper runs them in
 * @param now when the Check runs
 */
public record ClaimRound(String leagueId, ZonedDateTime deadline, Instant now) {

    /** The user's zone. Pinned here, not read from the clock: the Check runs in UTC. */
    private static final ZoneId BOARD_ZONE = ZoneId.of("America/Los_Angeles");

    private static final LocalTime BOARD_TIME = LocalTime.of(18, 0);

    /**
     * Returns the league's next claim round, empty when its claim day
     * is unknown.
     */
    public static Optional<ClaimRound> next(SleeperAdapter.League league, Instant now) {
        return league.rules().nextClaimDeadline(now)
                .map(deadline -> new ClaimRound(league.leagueId(), deadline, now));
    }

    /**
     * Returns when the round's waiver board is due: 18:00
     * America/Los_Angeles on the calendar day before the claim deadline,
     * read in the zone Sleeper runs claims in.
     */
    public ZonedDateTime due() {
        return deadline.withZoneSameInstant(LeagueRules.CLAIM_ZONE).toLocalDate().minusDays(1)
                .atTime(BOARD_TIME)
                .atZone(BOARD_ZONE);
    }

    /** True from the evening the board is due until the claim deadline. */
    public boolean open() {
        return !now.isBefore(due().toInstant());
    }

    /** Returns the Alert key the round's waiver board is sent under. */
    public String alertKey() {
        return "alert:waiver:" + due().toLocalDate();
    }
}
