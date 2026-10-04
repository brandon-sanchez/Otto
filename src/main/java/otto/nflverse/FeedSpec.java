package otto.nflverse;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * One registered feed. The engine in {@link NflverseFeedService} owns the
 * timestamp check, the download, coverage and failure isolation; a spec owns only
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

    Grain grain();

    default Due due() {
        return Due.WHEN_FINAL;
    }

    /** Which coverage unit a kept row belongs to. */
    Coverage.Unit unit(R row);

    /** The feed's own publish stamp for a row, when the file carries one. */
    default Optional<Instant> stamp(R row) {
        return Optional.empty();
    }

    /**
     * The part of a row a correction is measured on: the whole row unless
     * a spec leaves out its stamp or anything else whose change is not
     * news to the board.
     */
    default Object content(R row) {
        return row;
    }

    /**
     * Bumped whenever {@link #content} changes shape, so that fingerprints
     * taken before are replaced rather than read as a correction of every
     * unit.
     */
    default int contentVersion() {
        return 1;
    }

    /** Builds the document to store, for a fresh download and for a re-checked unchanged one. */
    D document(Basis basis, Instant assetUpdatedAt, Instant checkedAt, List<R> rows,
            Coverage coverage);

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

    /** What one coverage unit is, which decides what the schedule expects of a week. */
    enum Grain {

        /** One unit per game id. */
        GAME,

        /** One unit per team in each game of the week. */
        TEAM_WEEK,

        /**
         * One unit per team with no week in the file. A team's unit counts
         * for every week whose window opened on or before its stamp.
         */
        TEAM_SNAPSHOT
    }

    /** When the schedule starts expecting a game's units. */
    enum Due {

        /** Once the game has a result. */
        WHEN_FINAL,

        /** As soon as the game is on the schedule: the feed is published before kickoff. */
        WHEN_SCHEDULED
    }

    /** The season a feed's file was named for, and whether it is last season's final record. */
    record Basis(String season, boolean priorSeasonFinal) {
    }
}
