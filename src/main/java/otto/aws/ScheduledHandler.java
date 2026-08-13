package otto.aws;

import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import otto.check.CheckResult;
import otto.check.CheckRunner;
import otto.nflverse.DefenseVersusPositionBuilder;
import otto.nflverse.NflverseFeedService;
import otto.storage.JsonStore;

/**
 * The scheduled entry point. Three EventBridge schedules reach it and
 * name the job they want: the 1-minute Check, the hourly nflverse
 * timestamp check, and the nightly defense-versus-position build. The
 * Lock Ladder, the Tuesday waiver Alert and the 20-minute Done
 * follow-up need no schedule of their own - they ride the 1-minute
 * loop and the Event Log.
 *
 * <p>One function serves all three, so one warm context serves all
 * three. Every run flushes the storage batch, whether or not it
 * finished, so a run that half succeeded still keeps what it learned.
 */
public class ScheduledHandler implements RequestHandler<Map<String, Object>, String> {

    private static final Logger log = LoggerFactory.getLogger(ScheduledHandler.class);

    /** The field an EventBridge schedule sets to pick its job. */
    static final String JOB = "job";

    /**
     * The line the heartbeat alarm counts. A Check that ran is a Check
     * loop that is alive, whether or not the pre-draft cadence let it
     * do any work, so the marker sits outside that branch.
     */
    static final String HEARTBEAT = "heartbeat check-completed";

    @Override
    public String handleRequest(Map<String, Object> event, Context context) {
        String job = String.valueOf(event.getOrDefault(JOB, ScheduledJob.CHECK.jobName()));
        ScheduledJob scheduled = ScheduledJob.of(job);
        return StoredRun.storing(OttoRuntime.bean(JsonStore.class), () -> run(scheduled));
    }

    private String run(ScheduledJob job) {
        return switch (job) {
            case CHECK -> check();
            case NFLVERSE_FEEDS -> nflverseFeeds();
            case DEFENSE_VERSUS_POSITION -> defenseVersusPosition();
        };
    }

    private String check() {
        CheckResult result = OttoRuntime.bean(CheckRunner.class).runCheck();
        log.info(HEARTBEAT);
        if (result.skipped()) {
            return "Check skipped by pre-draft cadence";
        }
        return "Check done: %d new events, %d alerts sent"
                .formatted(result.newEvents().size(), result.alertsSent().size());
    }

    private String nflverseFeeds() {
        return "nflverse feeds: " + OttoRuntime.bean(NflverseFeedService.class).updateIfDue();
    }

    private String defenseVersusPosition() {
        OttoRuntime.bean(DefenseVersusPositionBuilder.class).build();
        return "defense-versus-position built";
    }
}
