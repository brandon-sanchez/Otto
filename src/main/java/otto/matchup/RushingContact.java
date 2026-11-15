package otto.matchup;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import otto.nflverse.NflverseStore;
import otto.nflverse.PlayerIdMap;
import otto.nflverse.RushingAdvancedStats;

/**
 * Each runner's rushing advanced stats, by Sleeper id, read from the
 * stored rushing advanced stats. The join goes through the id map's PFR
 * ids, never a name; a row it cannot place belongs to no runner.
 */
public final class RushingContact {

    private final Map<String, List<RushingAdvancedStats.RushLine>> runnersBySleeper;

    private RushingContact(Map<String, List<RushingAdvancedStats.RushLine>> runnersBySleeper) {
        this.runnersBySleeper = runnersBySleeper;
    }

    /**
     * Builds the reading from the stored rushing feed and id map. Without
     * either, every runner reads as having no games.
     */
    public static RushingContact from(NflverseStore store) {
        Optional<RushingAdvancedStats> stats = store.rushingAdvancedStats();
        Optional<PlayerIdMap> ids = store.playerIds();
        if (stats.isEmpty() || ids.isEmpty()) {
            return new RushingContact(Map.of());
        }
        Map<String, List<RushingAdvancedStats.RushLine>> runners = stats.get().rows().stream()
                .sorted(Comparator.comparingInt(RushingAdvancedStats.RushLine::week))
                .flatMap(line -> ids.get().sleeperForPfr(line.pfrId())
                        .map(sleeperId -> Map.entry(sleeperId, line)).stream())
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toUnmodifiableList())));
        return new RushingContact(Map.copyOf(runners));
    }

    /** The runner's games in week order, or an empty list when he has none on file. */
    public List<RushingAdvancedStats.RushLine> runner(String sleeperId) {
        return runnersBySleeper.getOrDefault(sleeperId, List.of());
    }
}
