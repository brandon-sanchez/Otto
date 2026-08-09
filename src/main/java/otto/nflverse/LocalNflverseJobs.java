package otto.nflverse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Local stand-in for the two nflverse EventBridge schedules: the hourly
 * timestamp check and the nightly defense-versus-position build. The
 * nightly run sits after the last game of any night and before the
 * user's morning, in the user's own time zone.
 */
@Component
@Profile("local")
public class LocalNflverseJobs {

    private static final Logger log = LoggerFactory.getLogger(LocalNflverseJobs.class);

    private final NflverseFeedService feeds;
    private final DefenseVersusPositionBuilder defenseBuilder;

    public LocalNflverseJobs(NflverseFeedService feeds,
            DefenseVersusPositionBuilder defenseBuilder) {
        this.feeds = feeds;
        this.defenseBuilder = defenseBuilder;
    }

    @Scheduled(fixedDelayString = "PT1H")
    public void hourlyTimestampCheck() {
        log.info("nflverse feeds: {}", feeds.updateIfDue());
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "America/Los_Angeles")
    public void nightlyDefenseBuild() {
        defenseBuilder.build();
    }
}
