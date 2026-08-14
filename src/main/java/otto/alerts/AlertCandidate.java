package otto.alerts;

import java.util.Map;

import otto.settings.Trigger;

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
     * What produced the candidate. Transitions, trades, drops and
     * Watchlist hits are Snapshot Diff driven and fire once; legality
     * and edge problems are recomputed from current state on every
     * Check.
     *
     * A trade, a drop and a Watchlist hit are league news: they report
     * what somebody else did, so they carry no lineup Recommendation
     * and never take part in the Lock Ladder.
     *
     * Each source names the trigger the user can switch off and the
     * class a Mute can silence, so both mappings live here and nowhere
     * else. Two sources may share one trigger - a trade and a drop are
     * both league activity - while keeping mute classes of their own,
     * because the user may well want the trades and not the drops.
     */
    public enum Source {

        TRANSITION(Trigger.STATUS_TRANSITION, "class:transition", true),
        LEGALITY(Trigger.LINEUP_LEGALITY, "class:legality", false),
        EDGE(Trigger.BENCH_EDGE, "class:edge", false),
        WATCHLIST(Trigger.WATCHLIST, "class:watchlist", true),
        /**
         * A player changed rosters: a trade, or the Commissioner Edit
         * that does the same thing without anyone agreeing to it. Both
         * are the news the user opted into when he asked to hear about
         * trades, so one mute class covers them.
         */
        TRADE(Trigger.LEAGUE_ACTIVITY, "class:trade", false),
        DROP(Trigger.LEAGUE_ACTIVITY, "class:drop", true);

        private final Trigger trigger;
        private final String muteClass;
        private final boolean aboutOnePlayersNews;

        Source(Trigger trigger, String muteClass, boolean aboutOnePlayersNews) {
            this.trigger = trigger;
            this.muteClass = muteClass;
            this.aboutOnePlayersNews = aboutOnePlayersNews;
        }

        public Trigger trigger() {
            return trigger;
        }

        public String muteClass() {
            return muteClass;
        }

        /**
         * True when the message is news about one player rather than a
         * problem with the lineup. A Mute on that player silences it.
         */
        public boolean aboutOnePlayersNews() {
            return aboutOnePlayersNews;
        }
    }
}
