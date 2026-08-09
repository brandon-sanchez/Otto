package otto.check;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Local stand-in for the EventBridge 1-minute schedule: run a Check
 * every 60 seconds. The pre-draft cadence gate inside the Check
 * decides whether the run does real work.
 */
@Component
@Profile("local")
@EnableScheduling
public class LocalCheckLoop {

    private static final Logger log = LoggerFactory.getLogger(LocalCheckLoop.class);

    private final CheckRunner checkRunner;

    public LocalCheckLoop(CheckRunner checkRunner) {
        this.checkRunner = checkRunner;
    }

    @Scheduled(fixedDelayString = "PT60S")
    public void tick() {
        CheckResult result = checkRunner.runCheck();
        if (result.skipped()) {
            log.debug("Check skipped by pre-draft cadence");
            return;
        }
        log.info("Check done: {} new events, {} alerts sent",
                result.newEvents().size(), result.alertsSent().size());
    }
}
