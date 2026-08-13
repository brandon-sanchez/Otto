package otto.aws;

import java.util.Arrays;

/**
 * The three scheduled jobs, named the way an EventBridge schedule
 * names them.
 *
 * <p>The stack spells the same names in its schedule payloads and
 * cannot import them: the deployable must not carry the CDK libraries,
 * so the two builds are separate. Each side pins its own spelling in a
 * test - {@code OttoStackTest.thereAreThreeSchedules} for the payloads,
 * {@code AwsEntryPointScenarioTest.everyScheduleNamesAJobTheBuildHas}
 * for these - so a rename that reaches only one side fails a build
 * rather than a schedule.
 */
public enum ScheduledJob {

    CHECK("check"),
    NFLVERSE_FEEDS("nflverse-feeds"),
    DEFENSE_VERSUS_POSITION("defense-versus-position");

    private final String jobName;

    ScheduledJob(String jobName) {
        this.jobName = jobName;
    }

    public String jobName() {
        return jobName;
    }

    /**
     * An unknown job is refused rather than run as a Check. A schedule
     * that names a job this build does not have is a deployment
     * mistake, and running the wrong job would hide it.
     */
    public static ScheduledJob of(String jobName) {
        return Arrays.stream(values())
                .filter(job -> job.jobName.equals(jobName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No such scheduled job: " + jobName));
    }
}
