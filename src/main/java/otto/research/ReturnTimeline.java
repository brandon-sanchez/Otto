package otto.research;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * When an injured player is due back, as the news states it. Otto never
 * works out a return date itself: a timeline exists only when a story
 * says it in plain words, and every timeline carries the story it came
 * from so an answer can cite it.
 */
public sealed interface ReturnTimeline {

    /** How far back a story still counts: about one NFL season. */
    Duration NEWS_WINDOW = Duration.ofDays(150);

    /**
     * The story a timeline was read from.
     *
     * @param outlet the outlet the story is credited to
     * @param url the article address; empty when the story links nowhere
     * @param statement the sentence that states the timeline, as published
     */
    record Cited(String outlet, Optional<String> url, String statement, Instant published) {
    }

    /** The story says he is out for the rest of the season. */
    record SeasonEnding(Cited source) implements ReturnTimeline {
    }

    /**
     * The story gives how many weeks he misses, counted from when it was
     * published.
     *
     * @param fewest the short end of the range, the same as {@code most}
     *        when the story gives one number
     * @param most the long end, which Otto plans on
     */
    record WeeksOut(int fewest, int most, Cited source) implements ReturnTimeline {
    }

    /** The story names the NFL week he is due back. */
    record ByWeek(int week, Cited source) implements ReturnTimeline {
    }

    /** The story says there is no timetable for his return. */
    record NoTimetable(Cited source) implements ReturnTimeline {
    }

    /** No story states a timeline. */
    record Unknown() implements ReturnTimeline {
    }

    /**
     * Reads the timeline from the newest original story, published inside
     * {@link #NEWS_WINDOW} and not after now, that states one. A newer
     * story that states no timeline leaves an older one standing; a
     * syndicated copy is never read, because the original says the same.
     */
    static ReturnTimeline from(PlayerResearch research, Instant now) {
        Instant since = now.minus(NEWS_WINDOW);
        return research.items().stream()
                .filter(ResearchItem::original)
                .filter(item -> item.published() != null && !item.published().isBefore(since)
                        && !item.published().isAfter(now))
                .map(TimelineReading::of)
                .flatMap(Optional::stream)
                .findFirst()
                .orElseGet(Unknown::new);
    }
}
