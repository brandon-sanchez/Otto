package otto.board;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.advice.EvidenceItem;
import otto.directory.DirectoryPlayer;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;

/**
 * Sleeper's most-added players: attention, which is context for the
 * model and never a cutoff. The trending players join the pool so a
 * pickup the market agrees on is always weighed, and a player who is
 * not trending loses nothing for it.
 */
@Component
@Order(5)
class TrendingLane implements EvidenceLane {

    private static final int POOL_ENTRIES = 5;

    private final SleeperAdapter sleeper;

    TrendingLane(SleeperAdapter sleeper, OttoProperties properties) {
        this.sleeper = sleeper.cachedWithin(properties.sleeperCadence());
    }

    @Override
    public String name() {
        return "TrendingLane";
    }

    @Override
    public Read read(Inputs inputs) {
        SourceResult<List<SleeperAdapter.TrendingPlayer>> trending =
                sleeper.trendingAdds(SleeperAdapter.TRENDING_LOOKBACK_HOURS,
                        SleeperAdapter.TRENDING_LIMIT);
        Map<String, Integer> adds = new LinkedHashMap<>();
        if (trending instanceof SourceResult.Ok<List<SleeperAdapter.TrendingPlayer>> ok) {
            ok.value().forEach(player -> adds.putIfAbsent(player.playerId(), player.adds()));
        }
        return new Read() {
            @Override
            public Map<String, String> shortlist(List<DirectoryPlayer> freeAgents) {
                Set<String> free = freeAgents.stream().map(DirectoryPlayer::playerId)
                        .collect(Collectors.toSet());
                Map<String, String> pool = new LinkedHashMap<>();
                adds.keySet().stream()
                        .filter(free::contains)
                        .limit(POOL_ENTRIES)
                        .forEach(playerId -> pool.put(playerId,
                                "among the free agents most added on Sleeper in the last day"));
                return pool;
            }

            @Override
            public List<EvidenceItem> evidence(DirectoryPlayer player) {
                if (!(trending instanceof SourceResult.Ok)) {
                    return List.of();
                }
                Integer count = adds.get(player.playerId());
                String value = count == null
                        ? "not among the %d players most added on Sleeper"
                                .formatted(SleeperAdapter.TRENDING_LIMIT)
                        : String.valueOf(count);
                return List.of(new EvidenceItem("trending-" + player.playerId(),
                        "sleeper:trending-add", Optional.empty(), inputs.now(),
                        "attention: how many Sleeper leagues added him in the last %d hours"
                                .formatted(SleeperAdapter.TRENDING_LOOKBACK_HOURS),
                        Map.of("addsLastDay", value)));
            }

            @Override
            public List<String> gaps() {
                return trending instanceof SourceResult.Unavailable<?> down
                        ? List.of("Sleeper's trending adds could not be read: " + down.reason())
                        : List.of();
            }
        };
    }
}
