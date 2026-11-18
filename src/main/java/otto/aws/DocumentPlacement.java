package otto.aws;

import java.util.Set;

import otto.storage.DocumentNaming;

/**
 * Which store a document belongs in: an S3 object of its own, the S3
 * bundle, or DynamoDB.
 *
 * <p>{@link #FEED} is the nflverse feeds and the team-reports state.
 * Play-by-play and FTN charting run to megabytes a season, so keeping
 * the feeds out of the bundle every Check reads means a feed is fetched
 * only by the code that reads it. The team-reports state is rewritten
 * every few minutes; in the bundle, each rewrite would cost every Check
 * after it a full bundle read in place of a 304.
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
 * its newest turns, three markers, the list of followed leagues, the
 * league the chat is about, each Alert delivery record, where each
 * waiver board's full list resumes, and the research cache: one
 * player's stories, capped at a fixed count, and the index of when
 * each player was last read. The market values are small too: one day
 * of one format's list is a few hundred players, and the days are kept
 * in a fixed ring of slots. Big means everything that grows with the
 * league, the roster of players or the season - the Snapshots, the
 * Player Directory, defense-versus-position and the Event Log.
 */
public enum DocumentPlacement {

    /** An S3 object of its own, read and written by {@link S3FeedObjects}. */
    FEED,

    /** The one S3 object every other big document shares, {@link S3BundleBackend}. */
    BUNDLE,

    /** A DynamoDB item, {@link DynamoDbBackend}. */
    SMALL;

    private static final String FEED_NAME_PREFIX = "nflverse-";

    private static final String TEAM_REPORTS = "team-reports";

    private static final Set<String> SMALL_NAMES = Set.of(
            "settings",
            "watchlist",
            "watchlist-observations",
            "mutes",
            "conversation",
            "last-check",
            "alert-id-sequence",
            "telegram-updates",
            "account-deletion-request",
            "followed-leagues",
            "chat-league");

    private static final Set<String> SMALL_PREFIXES = Set.of(
            "alert-delivery-",
            "board-options-sent-",
            "research-",
            "market-values-");

    /**
     * Returns where the document with this name is stored. A league's
     * own copy of a document is stored where the config league's copy
     * is, so the league suffix is read past.
     */
    public static DocumentPlacement of(String stored) {
        String name = DocumentNaming.withoutLeague(stored);
        if (name.startsWith(FEED_NAME_PREFIX) || name.equals(TEAM_REPORTS)) {
            return FEED;
        }
        if (SMALL_NAMES.contains(name) || SMALL_PREFIXES.stream().anyMatch(name::startsWith)) {
            return SMALL;
        }
        return BUNDLE;
    }

    /** True for the team-reports state, which is a feed under its own key prefix. */
    static boolean isTeamReports(String name) {
        return name.equals(TEAM_REPORTS);
    }
}
