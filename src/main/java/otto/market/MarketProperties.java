package otto.market;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the market-value sources live.
 *
 * @param fantasyCalcBaseUrl the host of FantasyCalc's values API
 * @param dynastyProcessBaseUrl the host that serves DynastyProcess's
 *        open data files
 */
@ConfigurationProperties(prefix = "otto.market")
public record MarketProperties(String fantasyCalcBaseUrl, String dynastyProcessBaseUrl) {
}
