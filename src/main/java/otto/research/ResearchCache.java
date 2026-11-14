package otto.research;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;
import otto.storage.JsonStore;

/**
 * The research cache: the news stories Otto has read about each
 * player, with where each came from and when it was published and
 * read. One document per player, stored as a DynamoDB record, shared
 * by every league.
 *
 * <p>A read keeps only what is new. A story already held with the same
 * words is skipped, a story held under the same outlet id or address
 * with different words replaces the old version, and a story whose
 * words repeat one already held is kept as a copy of it. Only new and
 * changed original reports come back as fresh, and the document is
 * written only when something changed.
 *
 * <p>Two runs that read one player at once may both write; the later
 * write wins, and a story it dropped is read again as new next time.
 */
@Component
public class ResearchCache {

    /** How many stories one read asks Sleeper for, newest first. */
    static final int FEED_ITEMS = 10;

    /**
     * How many stories one player's document keeps, newest first. It
     * bounds the record well inside DynamoDB's 400 KB item limit, and it
     * is larger than one read, so a story still in the feed is never
     * trimmed and read again as new.
     */
    static final int MAX_ITEMS_PER_PLAYER = 40;

    private static final String DOCUMENT_PREFIX = "research-player-";

    /** What a feed calls a story's outlet when it names none. */
    private static final String UNKNOWN_SOURCE = "unknown";

    private final SleeperAdapter sleeper;
    private final JsonStore store;
    private final Clock clock;

    public ResearchCache(SleeperAdapter sleeper, JsonStore store, Clock clock) {
        this.sleeper = sleeper;
        this.store = store;
        this.clock = clock;
    }

    /**
     * The outcome of reading one player's feed.
     *
     * <p>{@link Read} carries the stories that were new or changed;
     * {@link Unavailable} carries why the feed could not be read. Both
     * carry what the cache holds afterwards.
     */
    public sealed interface Refresh {

        /** What the cache holds about the player after this read. */
        PlayerResearch research();

        /** The new or changed original reports this read found, newest first. */
        List<ResearchItem> fresh();

        /** The feed was read. */
        record Read(PlayerResearch research, List<ResearchItem> fresh) implements Refresh {
        }

        /**
         * The feed could not be read, so the cache is unchanged.
         *
         * @param reason why, as the Sleeper wire put it
         */
        record Unavailable(PlayerResearch research, String reason) implements Refresh {

            @Override
            public List<ResearchItem> fresh() {
                return List.of();
            }
        }
    }

    /** Returns what the cache holds about the player, empty when it holds nothing. */
    public PlayerResearch read(String playerId) {
        return store.read(DOCUMENT_PREFIX + playerId, PlayerResearch.class)
                .orElseGet(() -> PlayerResearch.empty(playerId));
    }

    /**
     * Reads the player's news feed live and keeps what is new.
     *
     * <p>Side effects: one Sleeper request, and one DynamoDB write when
     * the feed held a new or changed story.
     */
    public Refresh refresh(String playerId) {
        PlayerResearch held = read(playerId);
        return switch (sleeper.playerNews(playerId, FEED_ITEMS)) {
            case SourceResult.Unavailable<List<SleeperAdapter.NewsItem>> down ->
                new Refresh.Unavailable(held, down.reason());
            case SourceResult.Ok<List<SleeperAdapter.NewsItem>> ok -> keep(held, ok.value());
        };
    }

    private Refresh keep(PlayerResearch held, List<SleeperAdapter.NewsItem> feed) {
        Instant now = clock.instant();
        List<ResearchItem> items = new ArrayList<>(held.items());
        List<ResearchItem> fresh = new ArrayList<>();
        boolean changed = false;
        // Oldest first, so the outlet that published a story first holds
        // the original and a later repeat is the copy.
        List<SleeperAdapter.NewsItem> oldestFirst = feed.stream()
                .sorted(Comparator.comparing(SleeperAdapter.NewsItem::published,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        for (SleeperAdapter.NewsItem story : oldestFirst) {
            Fingerprint fingerprint = Fingerprint.of(story.headline(), story.detail(),
                    story.analysis());
            String hash = fingerprint.contentHash();
            String url = CanonicalUrl.of(story.url());
            String source = blank(story.source()) ? UNKNOWN_SOURCE : story.source();
            String storyKey = storyKey(source, story.sourceKey(), url, hash);

            Optional<ResearchItem> sameStory = items.stream()
                    .filter(item -> item.storyKey().equals(storyKey)
                            || (url != null && url.equals(item.canonicalUrl())))
                    .findFirst();
            if (sameStory.isPresent() && sameStory.get().contentHash().equals(hash)) {
                continue;
            }
            String copyOf = sameStory.isPresent()
                    ? sameStory.get().copyOf()
                    : repeated(items, fingerprint, hash);
            ResearchItem item = new ResearchItem(
                    sameStory.map(ResearchItem::storyKey).orElse(storyKey),
                    source, url, story.headline(), story.detail(), story.analysis(),
                    story.published(), now, hash, copyOf,
                    ResearchTopic.of(story.headline(), story.detail(), story.analysis()));
            sameStory.ifPresent(items::remove);
            items.add(item);
            changed = true;
            if (item.original()) {
                fresh.add(item);
            }
        }
        if (!changed) {
            return new Refresh.Read(held, List.of());
        }
        PlayerResearch kept = new PlayerResearch(held.playerId(), items.stream()
                .sorted(PlayerResearch.NEWEST_FIRST)
                .limit(MAX_ITEMS_PER_PLAYER)
                .toList());
        store.write(DOCUMENT_PREFIX + held.playerId(), kept);
        return new Refresh.Read(kept, fresh.stream().sorted(PlayerResearch.NEWEST_FIRST).toList());
    }

    /**
     * The story key of the original report this text repeats, or null
     * when it repeats none. A copy of a copy points at the original.
     */
    private static String repeated(List<ResearchItem> items, Fingerprint fingerprint,
            String hash) {
        return items.stream()
                .filter(item -> item.contentHash().equals(hash)
                        || Fingerprint.of(item.headline(), item.detail(), item.analysis())
                                .nearDuplicateOf(fingerprint))
                .findFirst()
                .map(item -> item.original() ? item.storyKey() : item.copyOf())
                .orElse(null);
    }

    private static String storyKey(String source, String sourceKey, String url, String hash) {
        if (!blank(sourceKey)) {
            return source + ":" + sourceKey;
        }
        return url != null ? "url:" + url : "hash:" + hash;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
