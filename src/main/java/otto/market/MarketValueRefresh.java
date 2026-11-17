package otto.market;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;

/**
 * The daily market-value job: reads the lists the user's league needs,
 * so Otto's history gains a day even when nobody asks about a player.
 * {@link MarketValues} keeps it to one request a day for each source and
 * format, so running it more often costs nothing.
 */
@Component
public class MarketValueRefresh {

    private final SleeperAdapter sleeper;
    private final MarketValues values;
    private final Clock clock;

    public MarketValueRefresh(SleeperAdapter sleeper, MarketValues values, Clock clock) {
        this.sleeper = sleeper;
        this.values = values;
        this.clock = clock;
    }

    /**
     * Reads today's lists for the league's formats, and DynastyProcess's
     * table for a dynasty or keeper league.
     *
     * <p>Side effects: one Sleeper league read, then at most one request
     * a day to each source for each format, and the writes that store
     * what they return.
     *
     * @return one line on what was read, for the job's log
     */
    public String refresh() {
        Instant now = clock.instant();
        return switch (sleeper.league()) {
            case SourceResult.Unavailable<SleeperAdapter.League> unavailable ->
                    "league unavailable: " + unavailable.reason();
            case SourceResult.Ok<SleeperAdapter.League> league -> {
                List<MarketFormat> formats = MarketFormat.neededBy(league.value());
                if (formats.isEmpty()) {
                    yield "the league does not say its format or team count";
                }
                List<String> read = new ArrayList<>();
                for (MarketFormat format : formats) {
                    read.add(format.key() + " " + describe(values.fantasyCalc(format, now)));
                }
                if (formats.stream().anyMatch(MarketFormat::dynasty)) {
                    read.add("dynastyprocess " + describe(values.dynastyProcess(now)));
                }
                yield String.join("; ", read);
            }
        };
    }

    private static String describe(SourceResult<? extends MarketValues.Listing<?>> result) {
        return switch (result) {
            case SourceResult.Ok<? extends MarketValues.Listing<?>> ok ->
                    "from " + ok.value().day();
            case SourceResult.Unavailable<? extends MarketValues.Listing<?>> unavailable ->
                    "unavailable: " + unavailable.reason();
        };
    }
}
