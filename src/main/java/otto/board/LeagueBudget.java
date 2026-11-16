package otto.board;

import java.util.Optional;

import otto.ask.LeagueWeek;

/**
 * The FAAB budget each team in the league starts with, which a share of
 * the budget converts to dollars against, or the lack of one when
 * Sleeper does not say.
 *
 * @param dollars the starting budget; empty when unknown
 */
record LeagueBudget(Optional<Integer> dollars) {

    /** Returns the starting budget of the league in this league week. */
    static LeagueBudget of(LeagueWeek league) {
        return new LeagueBudget(league.league().waiverBudget());
    }

    /**
     * Returns the share in this league's dollars as a parenthetical,
     * such as " ($12 in your league)", or nothing when the budget is
     * unknown.
     *
     * @param share a fraction of the budget, from 0 to 1
     * @param where how the parenthetical names the league, such as
     *        "your league"
     */
    String inLeague(double share, String where) {
        return dollars.map(budget -> " ($%d in %s)".formatted(Math.round(share * budget), where))
                .orElse("");
    }
}
