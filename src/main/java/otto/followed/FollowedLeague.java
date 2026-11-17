package otto.followed;

import otto.sleeper.LeagueRules.LeagueFormat;

/**
 * One Sleeper league the owner plays in, as Otto names it to him.
 *
 * @param name the league's name on Sleeper; null when neither the
 *        league list nor the league's own document could be read
 * @param format redraft, keeper or dynasty; null when Sleeper does not say
 */
public record FollowedLeague(String leagueId, String name, LeagueFormat format) {

    /** Returns the name to show the owner, which falls back to the league id. */
    public String label() {
        return name == null || name.isBlank() ? "league " + leagueId : name;
    }
}
