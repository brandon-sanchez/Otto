package otto.nflverse;

import java.time.Instant;
import java.util.List;

/** Whether one feed holds one week, measured against the schedule. */
public sealed interface WeekStatus {

    /**
     * Every game of the week is final where the feed waits for results,
     * and every unit the schedule expects is held.
     *
     * @param asOf the newest change among the week's units: the version
     *        of this week the feed now holds
     * @param corrected the units whose content changed after Otto first saw them
     */
    record Complete(Instant asOf, List<Coverage.Unit> corrected) implements WeekStatus {
    }

    /**
     * @param missing the units the schedule expects and the feed lacks,
     *        in kickoff order
     * @param corrected the held units whose content changed after Otto first saw them
     */
    record Incomplete(List<Coverage.Unit> missing, List<Coverage.Unit> corrected)
            implements WeekStatus {
    }

    /**
     * Nothing can be said: there is no schedule, the feed was never
     * downloaded or holds another season, the week has not started or
     * is still being played, or the snapshot that described it has been
     * replaced by a newer one. Never a claim that the week is short.
     */
    record Unknown(String reason) implements WeekStatus {
    }
}
