package otto.nflverse;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * One registered feed. The engine in {@link NflverseFeedService} owns the
 * timestamp check, the download and failure isolation; a spec owns only
 * what differs between feeds.
 *
 * @param <R> the trimmed domain row the reader keeps
 * @param <D> the stored document
 */
interface FeedSpec<R, D extends NflverseFeed<R>> {

    String NFLVERSE_DATA = "nflverse/nflverse-data";

    FeedId id();

    /** The GitHub repository whose releases publish this feed. */
    default String repo() {
        return NFLVERSE_DATA;
    }

    String tag();

    String asset(String season);

    SeasonRule seasonRule();

    /**
     * A fold over the streamed rows that keeps only what the board reads.
     * Throws IllegalStateException on drift; the client turns it into a
     * typed unavailable result.
     */
    List<R> read(Basis basis, Stream<Csv.Row> rows);

    /** Builds the document to store, for a fresh download and for a re-checked unchanged one. */
    D document(Basis basis, Instant assetUpdatedAt, Instant checkedAt, List<R> rows);

    Class<D> type();

    default String documentName() {
        return "nflverse-" + id().name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** Which season's file a feed names for the Sleeper week in hand. */
    enum SeasonRule {

        /** The Sleeper season as published. */
        CURRENT,

        /** Last season's final file until a current-season week has been played. */
        LAST_PLAYED
    }

    /** The season a feed's file was named for, and whether it is last season's final record. */
    record Basis(String season, boolean priorSeasonFinal) {
    }
}
