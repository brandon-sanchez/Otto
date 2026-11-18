package otto.followed;

import java.util.Set;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.scope.LeagueScope;
import otto.storage.DocumentNaming;

/**
 * Keeps each followed league's own documents apart. A document that
 * belongs to a league is stored under its plain name for the league
 * named in config, and under its name plus {@link #LEAGUE_SUFFIX} and
 * the league id for every other league. A shared document keeps its
 * plain name for every league.
 *
 * <p>The config league's documents keep their plain names, so documents
 * stored under those names are its documents, with nothing to rename
 * or copy. ADR-0012 lists which documents belong to a league and why.
 *
 * <p>Every kind of document Otto stores is listed here as one or the
 * other, and the scenario tests fail on any document they touch that is
 * in neither list. A document missing from both is stored as shared.
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

    /**
     * Documents about the owner or the NFL rather than one league, and
     * documents whose entries carry their own league id.
     */
    private static final Set<String> SHARED = Set.of(
            "conversation",
            "chat-league",
            "followed-leagues",
            "watchlist",
            "player-directory",
            "defense-versus-position",
            "team-reports",
            "research-index",
            "waiver-preferences",
            "waiver-comparisons",
            "team-directions",
            "team-direction-questions",
            "keeper-rules",
            "alert-id-sequence",
            "telegram-updates",
            "account-deletion-request");

    /**
     * The nflverse feeds, ffopportunity's expected points among them, each
     * player's research, the market values kept per format, the delivery
     * records found by Alert id, and how far each waiver board's full
     * list went out.
     */
    private static final Set<String> SHARED_PREFIXES = Set.of(
            "nflverse-",
            "ffopportunity-",
            "research-player-",
            "market-values-",
            "alert-delivery-id:",
            "board-options-sent-");

    private final String configLeagueId;

    public LeagueDocumentNaming(OttoProperties properties) {
        this.configLeagueId = properties.leagueId();
    }

    /** Whether a kind of document belongs to one league or is shared by every league. */
    public enum Ownership {
        /** Each league keeps its own copy. */
        PER_LEAGUE,
        /** One copy serves every league. */
        SHARED,
        /** Listed as neither, which is stored as shared and is a mistake to fix. */
        UNLISTED
    }

    @Override
    public String stored(String name) {
        String league = LeagueScope.bound().orElse(configLeagueId);
        if (league.equals(configLeagueId) || ownershipOf(name) != Ownership.PER_LEAGUE) {
            return name;
        }
        return name + LEAGUE_SUFFIX + league;
    }

    /** Returns whether the document called {@code name} belongs to one league or is shared. */
    public static Ownership ownershipOf(String name) {
        if (PER_LEAGUE.contains(name) || startsWithAny(name, PER_LEAGUE_PREFIXES)) {
            return Ownership.PER_LEAGUE;
        }
        if (SHARED.contains(name) || startsWithAny(name, SHARED_PREFIXES)) {
            return Ownership.SHARED;
        }
        return Ownership.UNLISTED;
    }

    private static boolean startsWithAny(String name, Set<String> prefixes) {
        return prefixes.stream().anyMatch(name::startsWith);
    }
}
