package otto.followed;

import java.util.Set;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.sleeper.LeagueScope;
import otto.storage.DocumentNaming;

/**
 * Keeps each followed league's own documents apart. A document that
 * belongs to a league is stored under its plain name for the league
 * named in config, and under its name plus {@link #LEAGUE_SUFFIX} and
 * the league id for every other league. Every other document is shared
 * by all leagues and keeps its plain name.
 *
 * <p>The config league keeps the names it always had, so the documents
 * stored before Otto followed more than one league are its documents,
 * with nothing to rename or copy. ADR-0012 lists which documents
 * belong to a league and why.
 */
@Component
public class LeagueDocumentNaming implements DocumentNaming {

    private static final Set<String> PER_LEAGUE = Set.of(
            "settings",
            "mutes",
            "snapshot-current",
            "snapshot-previous",
            "last-check",
            "event-log",
            "unsaved-run-outcomes",
            "habit-weeks",
            "starter-points",
            "role-slips",
            "watchlist-observations");

    /**
     * The outbox's dedup pointers and leases: two leagues can raise an
     * Alert with the same key. The delivery records themselves are
     * found by Alert id, which is unique across leagues, and stay shared.
     */
    private static final Set<String> PER_LEAGUE_PREFIXES = Set.of(
            "alert-delivery-key:",
            "alert-delivery-lease:");

    private final String configLeagueId;

    public LeagueDocumentNaming(OttoProperties properties) {
        this.configLeagueId = properties.leagueId();
    }

    @Override
    public String stored(String name) {
        String league = LeagueScope.bound().orElse(configLeagueId);
        if (league.equals(configLeagueId) || !belongsToALeague(name)) {
            return name;
        }
        return name + LEAGUE_SUFFIX + league;
    }

    private static boolean belongsToALeague(String name) {
        return PER_LEAGUE.contains(name)
                || PER_LEAGUE_PREFIXES.stream().anyMatch(name::startsWith);
    }
}
