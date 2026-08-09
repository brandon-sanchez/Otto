package otto.watchlist;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import otto.alerts.SelfReportService;
import otto.check.WeekFacts;
import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;
import otto.lineup.ProjectionTable;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;
import otto.storage.JsonStore;

/**
 * Watches the two things about a Watchlist player that no Snapshot
 * carries: how hard the rest of the fantasy world is adding him, and
 * what this week's projection says about him. Both are diffed the same
 * way the Snapshot is - against what Otto last saw - so each move fires
 * once and not on every Check.
 *
 * Nothing reaches the wire while the Watchlist is empty. The list is
 * the whole reason to ask.
 */
@Component
public class WatchlistWatcher {

    /**
     * Sharply is Sleeper's own ranking: a player who was outside the
     * most-added list over the last day and is now inside it. That needs
     * no invented multiplier, and it fires once per entry.
     */
    private static final int TRENDING_LIMIT = 25;
    private static final int TRENDING_LOOKBACK_HOURS = 24;

    /**
     * How far a projection must move to be worth a message: one point
     * in league scoring, the same size as the bench-over-starter edge
     * the spec calls worth acting on. Anything smaller is feed drift.
     */
    private static final double PROJECTION_MOVE = 1.0;

    private static final String DOCUMENT = "watchlist-observations";

    /**
     * What Otto last saw. Each source carries its own record of whether
     * it has ever been read, because one can be down while the other
     * answers: comparing against a reading that never happened would
     * report the whole world as new.
     *
     * The trending list is stored whole rather than filtered to the
     * Watchlist, so a player added to the list while he is already
     * trending is not reported as having just entered.
     *
     * @param trendingRead whether the most-added list has ever answered
     * @param trending the most-added player ids at the last reading
     * @param projectionsWeek the week the stored projections belong to
     * @param projections each watched player's last projected points
     */
    public record Observed(boolean trendingRead, Set<String> trending, String projectionsWeek,
            Map<String, Double> projections) {

        static Observed nothingYet() {
            return new Observed(false, null, null, null);
        }

        public Observed {
            trending = trending == null ? Set.of() : Set.copyOf(trending);
            projections = projections == null ? Map.of() : Map.copyOf(projections);
        }
    }

    private final WatchlistStore watchlist;
    private final SleeperAdapter sleeper;
    private final EventLog eventLog;
    private final SelfReportService selfReport;
    private final JsonStore store;

    public WatchlistWatcher(WatchlistStore watchlist, SleeperAdapter sleeper, EventLog eventLog,
            SelfReportService selfReport, JsonStore store) {
        this.watchlist = watchlist;
        this.sleeper = sleeper;
        this.eventLog = eventLog;
        this.selfReport = selfReport;
        this.store = store;
    }

    /**
     * Reads both sources, records what moved, and remembers the new
     * reading. Events are appended before the reading is stored, so a
     * crash in between repeats a message rather than losing one.
     *
     * @return the events this Check appended
     */
    public synchronized List<Event> observe(WeekFacts week, Instant at) {
        List<WatchlistStore.Entry> watched = watchlist.all();
        if (watched.isEmpty()) {
            return List.of();
        }
        // Nothing to compare against yet means this Check learns the
        // state and reports none of it as a move.
        Observed last = store.read(DOCUMENT, Observed.class).orElseGet(Observed::nothingYet);
        List<Event> events = new ArrayList<>();

        Optional<Set<String>> trending = readTrending(watched, last, at, events);
        Reading projections = readProjections(watched, week, last, at, events);

        List<Event> appended = new ArrayList<>();
        for (Event event : events) {
            if (eventLog.append(event)) {
                appended.add(event);
            }
        }
        Observed reading = new Observed(
                last.trendingRead() || trending.isPresent(),
                trending.orElse(last.trending()),
                projections.week(),
                projections.points());
        if (!reading.equals(last)) {
            store.write(DOCUMENT, reading);
        }
        return appended;
    }

    /**
     * @return the most-added ids now, or empty when the feed is down -
     *         the stored reading then stays as it was, so the entry is
     *         still news once the feed is back
     */
    private Optional<Set<String>> readTrending(List<WatchlistStore.Entry> watched, Observed last,
            Instant at, List<Event> events) {
        SourceResult<List<SleeperAdapter.TrendingPlayer>> result =
                sleeper.trendingAdds(TRENDING_LOOKBACK_HOURS, TRENDING_LIMIT);
        selfReport.reportIfUnavailable(result);
        if (!(result instanceof SourceResult.Ok<List<SleeperAdapter.TrendingPlayer>> ok)) {
            return Optional.empty();
        }
        Map<String, Integer> adds = new LinkedHashMap<>();
        ok.value().forEach(player -> adds.putIfAbsent(player.playerId(), player.adds()));

        for (WatchlistStore.Entry entry : watched) {
            Integer count = adds.get(entry.playerId());
            if (!last.trendingRead() || count == null
                    || last.trending().contains(entry.playerId())) {
                continue;
            }
            events.add(new Event(
                    "watchlist:trending:%s:%d".formatted(entry.playerId(), at.getEpochSecond()),
                    EventType.WATCHLIST_MOVE,
                    at,
                    Map.of(
                            "playerId", entry.playerId(),
                            "player", entry.player(),
                            "team", entry.team() == null ? "" : entry.team(),
                            "adds", String.valueOf(count),
                            "lookbackHours", String.valueOf(TRENDING_LOOKBACK_HOURS),
                            "trendingLimit", String.valueOf(TRENDING_LIMIT))));
        }
        return Optional.of(new LinkedHashSet<>(adds.keySet()));
    }

    /** The projections to remember, and the week they belong to. */
    private record Reading(String week, Map<String, Double> points) {
    }

    /**
     * @return the projections to remember, which are the last ones when
     *         the projection table is missing
     */
    private Reading readProjections(List<WatchlistStore.Entry> watched, WeekFacts week,
            Observed last, Instant at, List<Event> events) {
        if (week.projections().isEmpty() || week.weekKey().isEmpty()) {
            return new Reading(last.projectionsWeek(), last.projections());
        }
        ProjectionTable table = week.projections().get();
        String weekKey = week.weekKey().get();
        // Every number changes when the week rolls over, and none of
        // that is news about a player. A new week is a new baseline.
        boolean sameWeek = weekKey.equals(last.projectionsWeek());

        Map<String, Double> current = new LinkedHashMap<>();
        for (WatchlistStore.Entry entry : watched) {
            Optional<Double> points = table.points(entry.playerId(), entry.position());
            if (points.isEmpty()) {
                // No projection is not a move: the table simply does not
                // price him this week, which the last reading must keep
                // rather than read as a fall to zero.
                Double previous = sameWeek ? last.projections().get(entry.playerId()) : null;
                if (previous != null) {
                    current.put(entry.playerId(), previous);
                }
                continue;
            }
            current.put(entry.playerId(), points.get());
            Double previous = sameWeek ? last.projections().get(entry.playerId()) : null;
            if (previous == null || Math.abs(points.get() - previous) < PROJECTION_MOVE) {
                continue;
            }
            events.add(new Event(
                    "watchlist:projection:%s:%d".formatted(entry.playerId(), at.getEpochSecond()),
                    EventType.WATCHLIST_MOVE,
                    at,
                    Map.of(
                            "playerId", entry.playerId(),
                            "player", entry.player(),
                            "team", entry.team() == null ? "" : entry.team(),
                            "week", weekKey,
                            "was", format(previous),
                            "now", format(points.get()),
                            "move", signed(points.get() - previous))));
        }
        return new Reading(weekKey, current);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String signed(double value) {
        return String.format(Locale.ROOT, "%+.1f", value);
    }
}
