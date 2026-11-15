package otto.research;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

/**
 * The last read before an Alert goes out. Injury, availability and
 * roster news is read again for the player the Alert is about, so the
 * message carries what is true now rather than what the cache held.
 * Rosters, budgets and league rules need no read here: the Check that
 * sends the Alert read them moments earlier.
 */
@Component
public class PreSendResearch {

    /**
     * How old a report may be and still be the latest word on the
     * player. Long enough to span a week's practice reports, and shorter
     * than the gap between two games.
     */
    static final Duration REPORT_WINDOW = Duration.ofDays(3);

    private final ResearchCache cache;
    private final Clock clock;

    public PreSendResearch(ResearchCache cache, Clock clock) {
        this.cache = cache;
        this.clock = clock;
    }

    /**
     * Reads the player's news feed live and returns the Alert facts it
     * supports.
     *
     * <p>The facts are {@code newsReadAt} when the feed was read, or
     * {@code newsUnavailable} with the reason when it was not, in which
     * case the cached reports stand in. When an original injury,
     * availability or roster report was published inside
     * {@link #REPORT_WINDOW}, the newest one adds {@code latestReport},
     * {@code latestReportSource} and {@code latestReportPublished}, and
     * {@code reportSources} names every independent outlet behind those
     * reports.
     *
     * <p>Side effects: one Sleeper request, and a research cache write
     * when the feed held something new.
     */
    public Map<String, String> factsFor(String playerId) {
        Instant now = clock.instant();
        ResearchCache.Refresh refresh = cache.refresh(playerId);
        Map<String, String> facts = new HashMap<>();
        switch (refresh) {
            case ResearchCache.Refresh.Read read -> facts.put("newsReadAt", now.toString());
            case ResearchCache.Refresh.Unavailable down ->
                facts.put("newsUnavailable", down.reason() == null ? "no detail" : down.reason());
        }
        List<ResearchItem> reports = refresh.research()
                .decisionChangingSince(now, REPORT_WINDOW);
        if (reports.isEmpty()) {
            return Map.copyOf(facts);
        }
        ResearchItem latest = reports.getFirst();
        facts.put("latestReport", Stream.of(latest.headline(), latest.detail())
                .filter(Objects::nonNull)
                .collect(Collectors.joining(": ")));
        facts.put("latestReportSource", latest.creditedSource());
        facts.put("latestReportPublished", latest.published().toString());
        Set<String> sources = new LinkedHashSet<>();
        reports.forEach(report -> sources.add(report.creditedSource()));
        facts.put("reportSources", String.join(", ", sources));
        return Map.copyOf(facts);
    }
}
