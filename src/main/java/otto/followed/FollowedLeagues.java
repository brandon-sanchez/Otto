package otto.followed;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.sleeper.LeagueScope;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;
import otto.storage.JsonStore;

/**
 * The Sleeper leagues Otto follows for the owner: every league his
 * account is in this season, less the ones he left out in chat. The
 * league named in config is always first.
 *
 * <p>The league list is read from Sleeper by {@link #refreshIfDue}, at
 * most once per {@code otto.league-discovery-interval}. A failed read
 * keeps the last good list. With no good list at all, Otto follows the
 * config league alone.
 */
@Component
public class FollowedLeagues {

    private static final Logger log = LoggerFactory.getLogger(FollowedLeagues.class);

    private static final String DOCUMENT = "followed-leagues";

    private final JsonStore store;
    private final SleeperAdapter sleeper;
    private final Clock clock;
    private final String configLeagueId;
    private final String userId;
    private final Duration interval;

    public FollowedLeagues(JsonStore store, SleeperAdapter sleeper, Clock clock,
            OttoProperties properties) {
        this.store = store;
        this.sleeper = sleeper;
        this.clock = clock;
        this.configLeagueId = properties.leagueId();
        this.userId = properties.userId();
        this.interval = properties.leagueDiscoveryInterval();
    }

    /**
     * The stored list.
     *
     * @param readAt when Sleeper's league list was last asked for,
     *        whether or not it answered
     * @param leagues the league list as last read, config league first
     * @param leftOut the ids of the leagues the owner left out
     */
    record Stored(Instant readAt, List<FollowedLeague> leagues, List<String> leftOut) {

        Stored {
            leagues = leagues == null ? List.of() : List.copyOf(leagues);
            leftOut = leftOut == null ? List.of() : List.copyOf(leftOut);
        }
    }

    /** What leaving a league out or bringing it back did. */
    public enum Change {
        /** The list changed. */
        CHANGED,
        /** The league was already in the state asked for. */
        ALREADY,
        /** The league is the last one followed, so it stays. */
        LAST_LEAGUE
    }

    /**
     * Returns every league Otto follows, config league first. The
     * config league is listed even when Sleeper's list leaves it out,
     * unless the owner left it out himself.
     */
    public List<FollowedLeague> followed() {
        Stored stored = stored();
        return known(stored).stream()
                .filter(league -> !stored.leftOut().contains(league.leagueId()))
                .toList();
    }

    /** Returns every league the owner plays in, the ones he left out included. */
    public List<FollowedLeague> known() {
        return known(stored());
    }

    /** True when the owner left the league with this id out. */
    public boolean leftOut(String leagueId) {
        return stored().leftOut().contains(leagueId);
    }

    /** True when Otto follows more than one league, so each message must name its league. */
    public boolean several() {
        return followed().size() > 1;
    }

    /** Returns the followed league with this id, or empty when Otto does not follow it. */
    public Optional<FollowedLeague> find(String leagueId) {
        return followed().stream()
                .filter(league -> league.leagueId().equals(leagueId))
                .findFirst();
    }

    /**
     * Reads Sleeper's league list again when the last read is older than
     * the discovery interval. A failed read is logged and keeps the last
     * good list; it is tried again after the same interval.
     *
     * <p>Side effects: one Sleeper read of the league list and one of the
     * NFL state, both conditional and cached, and one document write.
     */
    public synchronized void refreshIfDue() {
        Instant now = clock.instant();
        Stored stored = stored();
        if (stored.readAt() != null
                && Duration.between(stored.readAt(), now).compareTo(interval) < 0) {
            return;
        }
        List<FollowedLeague> leagues = stored.leagues();
        switch (read()) {
            case SourceResult.Ok<List<FollowedLeague>> ok -> leagues = ok.value();
            case SourceResult.Unavailable<List<FollowedLeague>> unavailable ->
                log.warn("Cannot read the owner's league list, so the leagues stay as they "
                        + "were: {}", unavailable.reason());
        }
        store.write(DOCUMENT, new Stored(now, leagues, stored.leftOut()));
    }

    /**
     * Leaves a league out of every Check, Alert and answer until the
     * owner brings it back. The last followed league cannot be left out.
     *
     * <p>Side effects: one document write when the list changes.
     */
    public synchronized Change leaveOut(String leagueId) {
        Stored stored = stored();
        if (stored.leftOut().contains(leagueId)) {
            return Change.ALREADY;
        }
        if (followed().stream().allMatch(league -> league.leagueId().equals(leagueId))) {
            return Change.LAST_LEAGUE;
        }
        List<String> leftOut = new ArrayList<>(stored.leftOut());
        leftOut.add(leagueId);
        store.write(DOCUMENT, new Stored(stored.readAt(), stored.leagues(), leftOut));
        return Change.CHANGED;
    }

    /**
     * Follows a league the owner left out again.
     *
     * <p>Side effects: one document write when the list changes.
     */
    public synchronized Change bringBack(String leagueId) {
        Stored stored = stored();
        if (!stored.leftOut().contains(leagueId)) {
            return Change.ALREADY;
        }
        List<String> leftOut = stored.leftOut().stream()
                .filter(id -> !id.equals(leagueId))
                .toList();
        store.write(DOCUMENT, new Stored(stored.readAt(), stored.leagues(), leftOut));
        return Change.CHANGED;
    }

    private SourceResult<List<FollowedLeague>> read() {
        return sleeper.nflState()
                .flatMap(state -> sleeper.userLeagues(userId, state.season()))
                .flatMap(leagues -> new SourceResult.Ok<>(configFirst(leagues.stream()
                        .map(league -> new FollowedLeague(league.leagueId(), league.name(),
                                league.format().orElse(null)))
                        .toList())));
    }

    /**
     * The config league first and the rest in Sleeper's order. When the
     * list does not name the config league, its own league document
     * names it, so the owner hears a name rather than an id.
     */
    private List<FollowedLeague> configFirst(List<FollowedLeague> listed) {
        List<FollowedLeague> leagues = new ArrayList<>();
        leagues.add(listed.stream()
                .filter(league -> league.leagueId().equals(configLeagueId))
                .findFirst()
                .orElseGet(this::configLeagueFromItsDocument));
        listed.stream()
                .filter(league -> !league.leagueId().equals(configLeagueId))
                .forEach(leagues::add);
        return List.copyOf(leagues);
    }

    private FollowedLeague configLeagueFromItsDocument() {
        return switch (LeagueScope.call(configLeagueId, sleeper::league)) {
            case SourceResult.Ok<SleeperAdapter.League> ok -> new FollowedLeague(configLeagueId,
                    ok.value().name(), ok.value().rules().format().orElse(null));
            case SourceResult.Unavailable<SleeperAdapter.League> unavailable ->
                new FollowedLeague(configLeagueId, null, null);
        };
    }

    /**
     * The stored list, or the config league alone when no list was ever
     * read. A lone league's messages never name it, so it needs no name.
     */
    private List<FollowedLeague> known(Stored stored) {
        return stored.leagues().isEmpty()
                ? List.of(new FollowedLeague(configLeagueId, null, null))
                : stored.leagues();
    }

    private Stored stored() {
        return store.read(DOCUMENT, Stored.class)
                .orElseGet(() -> new Stored(null, List.of(), List.of()));
    }
}
