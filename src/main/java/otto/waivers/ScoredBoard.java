package otto.waivers;

import java.time.Instant;
import java.util.function.Supplier;

import otto.league.LeagueWeek;
import otto.sleeper.SourceResult;

/**
 * One Check's scored board: the top {@value WaiverQuery#ALERT_COUNT}
 * free agents by {@link WaiverScorer}. Ranking reads live news, so it
 * runs on the first {@link #get} and the waiver Alert and the waiver
 * baselines share the result. Each Check makes its own.
 */
public final class ScoredBoard implements Supplier<SourceResult<WaiverBoard>> {

    private final WaiverScorer scorer;
    private final LeagueWeek league;
    private final Instant now;
    private SourceResult<WaiverBoard> ranked;

    ScoredBoard(WaiverScorer scorer, LeagueWeek league, Instant now) {
        this.scorer = scorer;
        this.league = league;
        this.now = now;
    }

    /**
     * Returns the ranked board.
     *
     * <p>Side effects: the scorer's reads, on the first call.
     */
    @Override
    public SourceResult<WaiverBoard> get() {
        if (ranked == null) {
            ranked = scorer.rank(league, now, WaiverQuery.everyPosition(WaiverQuery.ALERT_COUNT));
        }
        return ranked;
    }
}
