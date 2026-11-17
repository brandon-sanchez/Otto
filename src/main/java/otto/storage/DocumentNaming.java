package otto.storage;

/**
 * Turns the name a caller gives a document into the name it is stored
 * under. {@link JsonStore} applies it on every read, write and lease,
 * so a caller names a document the same way whichever league it is
 * working for.
 */
public interface DocumentNaming {

    /**
     * What a league's own copy of a document carries after its name,
     * followed by the league id. The league named in config carries
     * none, so its documents keep the names they always had.
     */
    String LEAGUE_SUFFIX = "@league-";

    /** Names every document as its caller does. */
    DocumentNaming AS_GIVEN = name -> name;

    /** Returns the name the document called {@code name} is stored under right now. */
    String stored(String name);

    /** Returns the name without any league suffix, which names its kind of document. */
    static String withoutLeague(String stored) {
        int suffix = stored.indexOf(LEAGUE_SUFFIX);
        return suffix < 0 ? stored : stored.substring(0, suffix);
    }
}
