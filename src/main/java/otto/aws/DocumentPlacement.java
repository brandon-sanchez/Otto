package otto.aws;

import java.util.Set;

/**
 * Which store a document belongs in: an S3 object of its own, the S3
 * bundle, or DynamoDB.
 *
 * <p>{@link #FEED} is the nflverse feeds. Play-by-play and FTN
 * charting run to megabytes a season, so keeping the feeds out of the
 * bundle every Check reads means a feed is fetched only by the code
 * that reads it.
 *
 * <p>{@link #SMALL} is the named list, and everything else is big and
 * goes to the {@link #BUNDLE}. That way round rather than the other
 * because of what each mistake costs. A new document that should have
 * been small and goes to S3 costs a slot in the bundle and nothing
 * else. A new document that should have been big and goes to DynamoDB
 * works until the season makes it pass the 400 KB item limit, which is
 * the middle of a season and not a deployment. So a document nobody
 * has thought about lands in the store that cannot be outgrown.
 *
 * <p>Small means a record whose size is settled by its shape: the
 * user's Settings, Watchlist and Mutes, one conversation trimmed to
 * its newest turns, three markers, and each Alert delivery record. Big
 * means everything that grows with the league, the roster of players
 * or the season - the Snapshots, the Player Directory,
 * defense-versus-position and the Event Log.
 */
public enum DocumentPlacement {

    /** An S3 object of its own, read and written by {@link S3FeedObjects}. */
    FEED,

    /** The one S3 object every other big document shares, {@link S3BundleBackend}. */
    BUNDLE,

    /** A DynamoDB item, {@link DynamoDbBackend}. */
    SMALL;

    private static final String FEED_NAME_PREFIX = "nflverse-";

    private static final Set<String> SMALL_NAMES = Set.of(
            "settings",
            "watchlist",
            "watchlist-observations",
            "mutes",
            "conversation",
            "last-check",
            "alert-id-sequence",
            "telegram-updates");

    private static final Set<String> SMALL_PREFIXES = Set.of("alert-delivery-");

    /** Returns where the document with this name is stored. */
    public static DocumentPlacement of(String name) {
        if (name.startsWith(FEED_NAME_PREFIX)) {
            return FEED;
        }
        if (SMALL_NAMES.contains(name) || SMALL_PREFIXES.stream().anyMatch(name::startsWith)) {
            return SMALL;
        }
        return BUNDLE;
    }
}
