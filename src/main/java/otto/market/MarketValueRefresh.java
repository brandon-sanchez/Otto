package otto.market;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import otto.followed.FollowedLeague;
import otto.followed.FollowedLeagues;
import otto.sleeper.LeagueScope;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;

/**
 * The daily market-value job: reads the lists the followed leagues need,
 * so Otto's history gains a day even when nobody asks about a player.
 * {@link MarketValues} keeps it to one request a day for each source and
 * format, so running it more often costs nothing.
 */
@Component
public class MarketValueRefresh {

    private final SleeperAdapter sleeper;
    private final MarketValues values;
    private final FollowedLeagues leagues;
    private final Clock clock;

    public MarketValueRefresh(SleeperAdapter sleeper, MarketValues values,
            FollowedLeagues leagues, Clock clock) {
        this.sleeper = sleeper;
        this.values = values;
        this.leagues = leagues;
        this.clock = clock;
    }

    /**
     * Reads today's lists for every followed league's formats, each
     * format once, and DynastyProcess's table when any league is dynasty
     * or keeper.
     *
     * <p>Side effects: one Sleeper read per followed league, then at
     * most one request a day to each source for each format, and the
     * writes that store what they return.
     *
     * @return one line on what was read, for the job's log
     */
    public String refresh() {
        Instant now = clock.instant();
        Set<MarketFormat> formats = new LinkedHashSet<>();
        List<String> read = new ArrayList<>();
        for (FollowedLeague league : leagues.followed()) {
            switch (LeagueScope.call(league.leagueId(), sleeper::league)) {
                case SourceResult.Unavailable<SleeperAdapter.League> unavailable ->
                        read.add("league unavailable: " + unavailable.reason());
                case SourceResult.Ok<SleeperAdapter.League> ok -> {
                    List<MarketFormat> needed = MarketFormat.neededBy(ok.value());
                    if (needed.isEmpty()) {
                        read.add("the league does not say its format or team count");
                    }
                    formats.addAll(needed);
                }
            }
        }
        for (MarketFormat format : formats) {
            read.add(format.key() + " " + describe(values.fantasyCalc(format, now)));
        }
        if (formats.stream().anyMatch(MarketFormat::dynasty)) {
            read.add("dynastyprocess " + describe(values.dynastyProcess(now)));
        }
        return String.join("; ", read);
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
