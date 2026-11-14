package otto.research;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Everything the research cache holds about one player, newest story
 * first. It is keyed by player and by nothing else, so every league
 * that asks about him reads the same research; what it means for a
 * roster is each league's own question.
 */
public record PlayerResearch(String playerId, List<ResearchItem> items) {

    /** Newest publication first; a story with no publication time sorts by when it was read. */
    static final Comparator<ResearchItem> NEWEST_FIRST = Comparator
            .comparing((ResearchItem item) -> item.published() != null
                    ? item.published() : item.retrieved())
            .reversed();

    public PlayerResearch {
        items = items.stream().sorted(NEWEST_FIRST).toList();
    }

    static PlayerResearch empty(String playerId) {
        return new PlayerResearch(playerId, List.of());
    }

    /**
     * Returns the credited outlets behind this player's original
     * reports, newest first. A syndicated copy adds nothing: it is the
     * same report, so it is not corroboration.
     */
    public Set<String> independentSources() {
        Set<String> sources = new LinkedHashSet<>();
        items.stream()
                .filter(ResearchItem::original)
                .map(ResearchItem::creditedSource)
                .forEach(sources::add);
        return sources;
    }

    /**
     * Returns the decision-changing original reports published inside
     * the window, newest first. A report with no publication time is
     * left out: when it was true cannot be told, and a read time alone
     * never makes an old report current.
     *
     * @param window how far back from now a report still counts
     */
    public List<ResearchItem> decisionChangingSince(Instant now, Duration window) {
        Instant since = now.minus(window);
        return items.stream()
                .filter(ResearchItem::original)
                .filter(ResearchItem::decisionChanging)
                .filter(item -> item.published() != null && !item.published().isBefore(since))
                .toList();
    }

    Optional<ResearchItem> byStoryKey(String storyKey) {
        return items.stream().filter(item -> item.storyKey().equals(storyKey)).findFirst();
    }
}
