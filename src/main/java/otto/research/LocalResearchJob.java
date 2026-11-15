package otto.research;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Local stand-in for the research and team-report EventBridge schedules. */
@Component
@Profile("local")
public class LocalResearchJob {

    private static final Logger log = LoggerFactory.getLogger(LocalResearchJob.class);

    private final ResearchDiscovery discovery;
    private final TeamReports teamReports;

    public LocalResearchJob(ResearchDiscovery discovery, TeamReports teamReports) {
        this.discovery = discovery;
        this.teamReports = teamReports;
    }

    /** Reads the next batch of news feeds, on the deployed schedule's cadence. */
    @Scheduled(fixedDelayString = "PT10M")
    public void discover() {
        log.info("research: {}", discovery.discover());
    }

    /** Reads the team sites' injury news, on the deployed schedule's cadence. */
    @Scheduled(fixedDelayString = "PT5M")
    public void teamReports() {
        log.info("team reports: {}", teamReports.poll());
    }
}
