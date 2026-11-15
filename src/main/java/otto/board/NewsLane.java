package otto.board;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.advice.EvidenceItem;
import otto.directory.DirectoryPlayer;
import otto.research.PlayerResearch;
import otto.research.ResearchCache;
import otto.research.ResearchItem;
import otto.research.ResearchTopic;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;

/**
 * News: the original reports the research cache holds about each
 * candidate from the past week. The board reads the cache only, never
 * the live feed; research discovery keeps it filled ahead of time.
 *
 * <p>The cache holds news for the players discovery reads, which
 * among free agents are Sleeper's most-added players. So the news lane
 * screens those, and reads news for every other candidate another lane
 * put in the pool.
 */
@Component
@Order(4)
class NewsLane implements EvidenceLane {

    /** How far back a report is still news for a waiver claim. */
    static final Duration WINDOW = Duration.ofDays(7);

    /** How many reports per candidate the evidence carries, newest first. */
    private static final int REPORTS = 3;

    private final ResearchCache cache;
    private final SleeperAdapter sleeper;

    NewsLane(ResearchCache cache, SleeperAdapter sleeper, OttoProperties properties) {
        this.cache = cache;
        this.sleeper = sleeper.cachedWithin(properties.sleeperCadence());
    }

    @Override
    public String name() {
        return "NewsLane";
    }

    @Override
    public Read read(Inputs inputs) {
        Map<String, List<ResearchItem>> read = new HashMap<>();
        Instant since = inputs.now().minus(WINDOW);
        return new Read() {
            @Override
            public Map<String, String> shortlist(List<DirectoryPlayer> freeAgents) {
                Set<String> trending = sleeper.trendingAdds(SleeperAdapter.TRENDING_LOOKBACK_HOURS,
                        SleeperAdapter.TRENDING_LIMIT) instanceof SourceResult.Ok<
                                List<SleeperAdapter.TrendingPlayer>> ok
                        ? ok.value().stream().map(SleeperAdapter.TrendingPlayer::playerId)
                                .collect(Collectors.toSet())
                        : Set.of();
                Map<String, String> pool = new LinkedHashMap<>();
                freeAgents.stream()
                        .filter(player -> trending.contains(player.playerId()))
                        .filter(player -> !reports(player).isEmpty())
                        .forEach(player -> pool.put(player.playerId(),
                                "a trending free agent with a news report from the past week"));
                return pool;
            }

            @Override
            public List<EvidenceItem> evidence(DirectoryPlayer player) {
                List<ResearchItem> reports = reports(player);
                if (reports.isEmpty()) {
                    return List.of(new EvidenceItem("news-" + player.playerId(),
                            "research-cache", Optional.empty(), inputs.now(),
                            "news from the past week",
                            Map.of("reports", "no report about him in the research cache "
                                    + "from the past week")));
                }
                List<EvidenceItem> items = new ArrayList<>();
                for (int index = 0; index < reports.size(); index++) {
                    ResearchItem report = reports.get(index);
                    Map<String, String> facts = new LinkedHashMap<>();
                    facts.put("report", Stream.of(report.headline(), report.detail())
                            .filter(Objects::nonNull)
                            .collect(Collectors.joining(": ")));
                    if (report.analysis() != null) {
                        facts.put("analysis", report.analysis());
                    }
                    facts.put("source", report.creditedSource());
                    if (!report.topics().isEmpty()) {
                        facts.put("reports on", report.topics().stream()
                                .map(ResearchTopic::name)
                                .map(String::toLowerCase)
                                .sorted()
                                .collect(Collectors.joining(", ")));
                    }
                    items.add(new EvidenceItem("news-%s-%d".formatted(player.playerId(), index + 1),
                            "research-cache:" + report.creditedSource(),
                            Optional.ofNullable(report.published()), report.retrieved(),
                            "news from the past week", facts));
                }
                return items;
            }

            @Override
            public List<String> gaps() {
                return List.of();
            }

            private List<ResearchItem> reports(DirectoryPlayer player) {
                return read.computeIfAbsent(player.playerId(), playerId -> {
                    PlayerResearch research = cache.read(playerId);
                    return research.items().stream()
                            .filter(ResearchItem::original)
                            .filter(item -> item.published() != null
                                    && !item.published().isBefore(since))
                            .limit(REPORTS)
                            .toList();
                });
            }
        };
    }
}
