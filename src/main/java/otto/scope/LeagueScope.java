package otto.scope;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * The Sleeper league one piece of work is for: one league's part of a
 * Check, one button tap, one chat answer. Sleeper reads and the
 * documents that belong to a league take their league from here. When
 * no league is bound, the work is for the league named in config.
 *
 * <p>The binding is a {@link ScopedValue}, so it lasts exactly as long
 * as the work and is never left behind for the next piece of work on
 * the same thread. It does not follow work onto another thread by
 * itself; {@link #carried} is how a hop takes it along.
 */
public final class LeagueScope {

    /** The league bound for the work; empty when a block is unbound from every league. */
    private static final ScopedValue<Optional<String>> LEAGUE = ScopedValue.newInstance();

    private LeagueScope() {
    }

    /**
     * Returns the league bound for this work, or empty when none is.
     * With none bound, documents that belong to a league are the config
     * league's and a message names no league.
     */
    public static Optional<String> bound() {
        return LEAGUE.orElse(Optional.empty());
    }

    /** Runs {@code work} for the league {@code leagueId} and returns what it returns. */
    public static <T> T call(String leagueId, Supplier<T> work) {
        return ScopedValue.where(LEAGUE, Optional.of(leagueId)).call(work::get);
    }

    /** Runs {@code work} for the league {@code leagueId}. */
    public static void run(String leagueId, Runnable work) {
        ScopedValue.where(LEAGUE, Optional.of(leagueId)).run(work);
    }

    /**
     * Runs {@code work} that is about no one league, even inside work
     * for a league, such as the report of a feed every league shares. A
     * message it sends names no league, and a document that belongs to
     * a league is the config league's, as when nothing is bound.
     */
    public static void runForNoLeague(Runnable work) {
        ScopedValue.where(LEAGUE, Optional.<String>empty()).run(work);
    }

    /**
     * Returns {@code work} wrapped to run for the league bound now,
     * wherever it later runs. Work handed to another thread loses the
     * binding otherwise and would read the config league.
     */
    public static <T> Callable<T> carried(Callable<T> work) {
        if (!LEAGUE.isBound()) {
            return work;
        }
        Optional<String> leagueId = LEAGUE.get();
        return () -> ScopedValue.where(LEAGUE, leagueId).call(work::call);
    }
}
