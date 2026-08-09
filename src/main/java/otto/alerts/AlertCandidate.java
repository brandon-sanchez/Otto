package otto.alerts;

import java.util.Map;

/**
 * One detected problem that may become an Alert. The key dedups
 * through the Event Log; the playerId groups problems about the same
 * player into one outbound message and ties the candidate to that
 * player's game lock.
 */
public record AlertCandidate(
        Source source,
        String key,
        String playerId,
        String team,
        Recommendation recommendation,
        Map<String, String> facts) {

    /**
     * What produced the candidate. Transitions, trades and drops are
     * Snapshot Diff driven and fire once; legality and edge problems
     * are recomputed from current state on every Check.
     *
     * A trade and a drop are league activity: they report what another
     * manager did, so they carry no lineup Recommendation and never
     * take part in the Lock Ladder.
     */
    public enum Source {
        TRANSITION, LEGALITY, EDGE, TRADE, DROP
    }
}
