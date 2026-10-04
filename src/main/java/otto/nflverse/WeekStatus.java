package otto.nflverse;

import java.time.Instant;
import java.util.List;

/** Whether one feed holds one week, measured against the schedule. */
public sealed interface WeekStatus {

    /**
     * Every unit the schedule expects is held.
     *
     * @param asOf the newest change among the week's units: the version
     *        of this week the feed now holds
     * @param corrected the units whose content changed after Otto first saw them
     */
    record Complete(Instant asOf, List<String> corrected) implements WeekStatus {
    }

    /**
     * @param missing the units the schedule expects and the feed lacks,
     *        in kickoff order
     * @param corrected the held units whose content changed after Otto first saw them
     */
    record Incomplete(List<String> missing, List<String> corrected) implements WeekStatus {
    }

    /**
     * Nothing can be said: there is no schedule, the feed was never
     * downloaded or holds another season, or the week has no game yet
     * that the feed is due for. Never a claim that the week is short.
     */
    record Unknown(String reason) implements WeekStatus {
    }
}
