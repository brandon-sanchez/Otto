package otto.aws;

import java.util.Set;

/**
 * Which store a document belongs in. The spec splits storage two ways:
 * big documents go to S3, small records go to DynamoDB.
 *
 * <p>Small is the named list, and everything else is big. That way
 * round rather than the other because of what each mistake costs. A
 * new document that should have been small and goes to S3 costs a slot
 * in the bundle and nothing else. A new document that should have been
 * big and goes to DynamoDB works until the season makes it pass the
 * 400 KB item limit, which is the middle of a season and not a
 * deployment. So a document nobody has thought about lands in the
 * store that cannot be outgrown.
 *
 * <p>Small means a record whose size is settled by its shape: the
 * user's Settings, Watchlist and Mutes, one conversation trimmed to
 * its newest turns, and three markers. Big means everything that grows
 * with the league, the roster of players or the season - the
 * Snapshots, the Player Directory, the nflverse feeds,
 * defense-versus-position and the Event Log.
 */
public final class DocumentPlacement {

    private static final Set<String> SMALL = Set.of(
            "settings",
            "watchlist",
            "watchlist-observations",
            "mutes",
            "conversation",
            "last-check",
            "alert-id-sequence",
            "telegram-updates");

    private DocumentPlacement() {
    }

    public static boolean isBig(String name) {
        return !SMALL.contains(name);
    }
}
