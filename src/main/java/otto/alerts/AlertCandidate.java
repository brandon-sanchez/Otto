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
     * What produced the candidate. Transitions are Snapshot Diff
     * driven and fire once per transition; legality and edge problems
     * are recomputed from current state on every Check.
     */
    public enum Source {
        TRANSITION, LEGALITY, EDGE
    }
}
