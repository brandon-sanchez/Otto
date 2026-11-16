package otto.market;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Local stand-in for the market-value EventBridge schedule. */
@Component
@Profile("local")
public class LocalMarketValueJob {

    private final MarketValueRefresh refresh;

    public LocalMarketValueJob(MarketValueRefresh refresh) {
        this.refresh = refresh;
    }

    @Scheduled(fixedDelayString = "PT1H")
    public void hourlyRefresh() {
        refresh.refresh();
    }
}
