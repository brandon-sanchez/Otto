package otto.offers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.ask.LeagueWeek;
import otto.check.SeasonWeek;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;

/**
 * How often each manager in the league has traded and moved players
 * this season, from Sleeper's weekly transactions.
 */
@Component
public class ManagerActivity {

    private final SleeperAdapter sleeper;

    public ManagerActivity(SleeperAdapter sleeper, OttoProperties properties) {
        this.sleeper = sleeper.cachedWithin(properties.sleeperCadence());
    }

    /**
     * Each team's trades and moves so far this season.
     *
     * @param trades completed trades each team was part of, by roster id
     * @param moves every completed transaction each team was part of:
     *        trades, waiver claims and free agent adds, by roster id
     * @param complete true when every week of the season so far was read,
     *        so a team with no moves really made none
     */
    public record Activity(Map<Integer, Integer> trades, Map<Integer, Integer> moves,
            boolean complete) {

        public Activity {
            trades = Map.copyOf(trades);
            moves = Map.copyOf(moves);
        }

        /** Returns how many trades the team has made this season. */
        public int tradesBy(int rosterId) {
            return trades.getOrDefault(rosterId, 0);
        }

        /** Returns how many transactions of any kind the team has made this season. */
        public int movesBy(int rosterId) {
            return moves.getOrDefault(rosterId, 0);
        }
    }

    /**
     * Reads every week of the season up to this one.
     *
     * <p>Side effects: one Sleeper transactions read per week, through
     * the cadence-gated adapter.
     */
    public Activity read(LeagueWeek league) {
        Map<Integer, Integer> trades = new HashMap<>();
        Map<Integer, Integer> moves = new HashMap<>();
        String season = league.week().seasonWeek().map(SeasonWeek::season).orElse("");
        int current = league.week().weekNumber().orElse(0);
        boolean complete = current > 0;
        for (int week = 1; week <= current; week++) {
            switch (sleeper.transactions(season, week)) {
                case SourceResult.Unavailable<List<SleeperAdapter.LeagueTransaction>> _ ->
                    complete = false;
                case SourceResult.Ok<List<SleeperAdapter.LeagueTransaction>> ok ->
                    ok.value().stream()
                            .filter(transaction -> !transaction.isCommissionerEdit())
                            .forEach(transaction -> transaction.rosterIds().forEach(id -> {
                                moves.merge(id, 1, Integer::sum);
                                if (transaction.isTrade()) {
                                    trades.merge(id, 1, Integer::sum);
                                }
                            }));
            }
        }
        return new Activity(trades, moves, complete);
    }
}
