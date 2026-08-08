package otto.check;

import java.time.Instant;

import otto.snapshot.LeagueStatus;

/** The last completed Check, stored to drive the cadence gate. */
public record LastCheck(Instant lastCheckAt, LeagueStatus leagueStatus) {
}
