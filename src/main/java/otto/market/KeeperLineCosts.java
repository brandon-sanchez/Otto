package otto.market;

import org.springframework.stereotype.Component;

/**
 * Prices every keeper at the keeper line. Sleeper publishes no keeper
 * cost, so what a keeper spot gives up is the only cost Otto can measure.
 */
@Component
public class KeeperLineCosts implements KeeperCosts {

    @Override
    public int costOf(String playerId, int keeperLine) {
        return keeperLine;
    }
}
