package otto.research;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;
import otto.snapshot.SnapshotStore;
import otto.storage.JsonStore;
import otto.watchlist.WatchlistStore;

/**
 * Reads news ahead of time, so research is already in the cache when
 * an Alert or a board needs it. A scheduled job runs it.
 *
 * <p>The players it reads are every rostered player in the latest
 * Snapshot, every Watchlist player, and Sleeper's most-added players.
 * One run reads at most {@link #BATCH} of them, least recently read
 * first, so a run stays a few seconds long and the whole set comes
 * round over a few runs.
 */
@Component
public class ResearchDiscovery {

    /**
     * Players read per run. The scheduled function runs one job at a
     * time, so a long run here would hold up the Check behind it.
     */
    static final int BATCH = 40;

    /**
     * The trending read the Watchlist watcher and the waiver board make,
     * at the same lookback and limit, so the three share one Sleeper cache
     * entry.
     */
    private static final int TRENDING_LOOKBACK_HOURS = 24;
    private static final int TRENDING_LIMIT = 25;

    private static final String INDEX = "research-index";

    private final ResearchCache cache;
    private final SnapshotStore snapshots;
    private final WatchlistStore watchlist;
    private final SleeperAdapter sleeper;
    private final JsonStore store;
    private final Clock clock;

    public ResearchDiscovery(ResearchCache cache, SnapshotStore snapshots,
            WatchlistStore watchlist, SleeperAdapter sleeper, JsonStore store,
            Clock clock) {
        this.cache = cache;
        this.snapshots = snapshots;
        this.watchlist = watchlist;
        this.sleeper = sleeper;
        this.store = store;
        this.clock = clock;
    }

    /**
     * What one run did.
     *
     * @param playersRead players whose feed was read
     * @param playersUnavailable players whose feed could not be read;
     *        they stay first in line for the next run
     * @param freshStories new or changed original reports found
     */
    public record Discovery(int playersRead, int playersUnavailable, int freshStories) {
    }

    /**
     * When each player's feed was last read. Pruned each run to the
     * players still worth reading, so it stays the size of the league.
     */
    record Index(Map<String, Instant> lastRead) {
    }

    /**
     * Reads the next batch of players' news into the research cache.
     *
     * <p>Side effects: one Sleeper request per player read plus the
     * trending read, a DynamoDB write per player with fresh news, and
     * one write of the read index.
     */
    public Discovery discover() {
        Instant now = clock.instant();
        Set<String> players = playersWorthReading();
        Map<String, Instant> lastRead = new HashMap<>(store.read(INDEX, Index.class)
                .map(Index::lastRead)
                .orElse(Map.of()));
        List<String> due = players.stream()
                .sorted(Comparator.comparing(
                        (String playerId) -> lastRead.getOrDefault(playerId, Instant.EPOCH)))
                .limit(BATCH)
                .toList();

        int read = 0;
        int unavailable = 0;
        int fresh = 0;
        for (String playerId : due) {
            ResearchCache.Refresh refresh = cache.refresh(playerId);
            if (refresh instanceof ResearchCache.Refresh.Unavailable) {
                unavailable++;
                continue;
            }
            read++;
            fresh += refresh.fresh().size();
            lastRead.put(playerId, now);
        }
        lastRead.keySet().retainAll(players);
        store.write(INDEX, new Index(lastRead));
        return new Discovery(read, unavailable, fresh);
    }

    private Set<String> playersWorthReading() {
        Set<String> players = new LinkedHashSet<>();
        snapshots.current().ifPresent(snapshot -> snapshot.rosters().stream()
                .map(RosterSnapshot::players)
                .forEach(players::addAll));
        players.addAll(watchlist.playerIds());
        // A trending list that cannot be read costs this run its trending
        // players and nothing else; the next run asks again.
        if (sleeper.trendingAdds(TRENDING_LOOKBACK_HOURS, TRENDING_LIMIT)
                instanceof SourceResult.Ok<List<SleeperAdapter.TrendingPlayer>> trending) {
            trending.value().stream()
                    .map(SleeperAdapter.TrendingPlayer::playerId)
                    .forEach(players::add);
        }
        return players;
    }
}
