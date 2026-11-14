package otto.research;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Local stand-in for the research EventBridge schedule. */
@Component
@Profile("local")
public class LocalResearchJob {

    private static final Logger log = LoggerFactory.getLogger(LocalResearchJob.class);

    private final ResearchDiscovery discovery;

    public LocalResearchJob(ResearchDiscovery discovery) {
        this.discovery = discovery;
    }

    /** Reads the next batch of news feeds, on the deployed schedule's cadence. */
    @Scheduled(fixedDelayString = "PT10M")
    public void discover() {
        log.info("research: {}", discovery.discover());
    }
}
