package otto.sleeper;

/**
 * The names Sleeper's sources carry in a {@link SourceResult.Unavailable},
 * and which of them hold one league's own data rather than data every
 * league shares.
 */
public final class SleeperSources {

    /** One league's document, read without its settings. */
    public static final String LEAGUE = "sleeper:league";

    /** One league's document, missing its roster positions or scoring settings. */
    public static final String LEAGUE_SETTINGS = "sleeper:league-settings";

    /** What every read of one league's own Sleeper path is named, path included. */
    private static final String LEAGUE_PATH = "sleeper:/v1/league/";

    private SleeperSources() {
    }

    /**
     * True when the source holds one league's own data: its document,
     * rosters, users, matchups or transactions. Every other source, such
     * as the NFL state, projections, news, the Player Directory or an
     * nflverse feed, is the same for every league.
     */
    public static boolean isLeagueOwned(String source) {
        return source.equals(LEAGUE) || source.equals(LEAGUE_SETTINGS)
                || source.startsWith(LEAGUE_PATH);
    }
}
