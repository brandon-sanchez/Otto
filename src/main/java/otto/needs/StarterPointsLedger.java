package otto.needs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.check.SeasonWeek;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SleeperAdapter.StarterLine;
import otto.sleeper.SourceResult;
import otto.storage.JsonStore;

/**
 * The stored record of what every team's starters actually scored in
 * each completed week of the season, read from Sleeper's matchups.
 *
 * <p>A completed week is stored once and read from storage after that,
 * so a Sleeper outage later in the season does not erase the history.
 * The latest completed week is always read again, because stat
 * corrections land through the Thursday after the games.
 */
@Component
public class StarterPointsLedger {

    private static final String DOCUMENT = "starter-points";

    private final SleeperAdapter sleeper;
    private final JsonStore store;

    public StarterPointsLedger(SleeperAdapter sleeper, JsonStore store,
            OttoProperties properties) {
        this.sleeper = sleeper.cachedWithin(properties.sleeperCadence());
        this.store = store;
    }

    record StoredSeason(String season, Map<Integer, List<StarterLine>> weeks) {

        StoredSeason {
            weeks = new TreeMap<>(weeks);
        }
    }

    /**
     * The completed weeks this ledger could account for.
     *
     * @param weeks week number to every team's starter line that week,
     *        for each completed week that Sleeper served now or once did
     * @param notes one sentence per completed week that is missing
     */
    public record Weeks(Map<Integer, List<StarterLine>> weeks, List<String> notes) {

        public Weeks {
            weeks = Map.copyOf(weeks);
            notes = List.copyOf(notes);
        }
    }

    /**
     * Reads every week of the season before the current one, from
     * storage where it can and from Sleeper where it must, and stores
     * what Sleeper served.
     *
     * <p>Side effects: one Sleeper read per week not yet stored plus
     * one for the latest completed week, answered from the cache while
     * it is younger than the cadence interval, and a write of the
     * stored document when anything in it changed.
     */
    public Weeks completedWeeks(SeasonWeek current) {
        Map<Integer, List<StarterLine>> weeks = new TreeMap<>(stored(current.season()));
        Map<Integer, List<StarterLine>> before = Map.copyOf(weeks);
        List<String> notes = new ArrayList<>();
        int latest = current.number() - 1;
        for (int week = 1; week <= latest; week++) {
            if (weeks.containsKey(week) && week < latest) {
                continue;
            }
            switch (sleeper.matchups(week)) {
                case SourceResult.Ok<List<StarterLine>> ok -> weeks.put(week, ok.value());
                case SourceResult.Unavailable<List<StarterLine>> unavailable -> {
                    if (!weeks.containsKey(week)) {
                        notes.add("I could not read week %d's starters from Sleeper (%s), so "
                                .formatted(week, unavailable.reason())
                                + "that week is left out of past scoring");
                    }
                }
            }
        }
        if (!weeks.equals(before)) {
            store.write(DOCUMENT, new StoredSeason(current.season(), weeks));
        }
        return new Weeks(weeks, notes);
    }

    private Map<Integer, List<StarterLine>> stored(String season) {
        Optional<StoredSeason> history = store.read(DOCUMENT, StoredSeason.class);
        return history.filter(stored -> stored.season().equals(season))
                .map(StoredSeason::weeks)
                .orElseGet(Map::of);
    }
}
