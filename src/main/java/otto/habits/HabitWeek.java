package otto.habits;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import otto.directory.PlayerHealth;

/**
 * What one NFL week of the user's own management left behind, as the
 * weekly capture stores it.
 *
 * @param week the regular-season week number
 * @param played the lineup and what it scored, once Sleeper has scored
 *        the week; empty before then, or when Sleeper never served it
 * @param kickoff each of the user's players' designation as last seen
 *        before his game started; a player whose game started before any
 *        Check saw him is absent, which is not the same as healthy
 * @param moves the user's transactions Sleeper files under this week,
 *        lost FAAB claims included
 * @param reserve the players on the user's IR, as last seen before each
 *        one's game started; empty for a week no Check saw live
 * @param couldGoOnIr the players on the user's bench who were eligible
 *        for IR while one of his IR slots was open, as last seen before
 *        each one's game started
 * @param clearings every FAAB claim won in the league this week, the
 *        user's and his rivals'
 */
public record HabitWeek(int week, Optional<PlayedWeek> played,
        Map<String, PlayerHealth> kickoff, List<Move> moves, Set<String> reserve,
        Set<String> couldGoOnIr, List<Clearing> clearings) {

    public HabitWeek {
        played = played == null ? Optional.empty() : played;
        kickoff = kickoff == null ? Map.of() : Map.copyOf(kickoff);
        moves = moves == null ? List.of() : List.copyOf(moves);
        reserve = reserve == null ? Set.of() : Set.copyOf(reserve);
        couldGoOnIr = couldGoOnIr == null ? Set.of() : Set.copyOf(couldGoOnIr);
        clearings = clearings == null ? List.of() : List.copyOf(clearings);
    }

    static HabitWeek empty(int week) {
        return new HabitWeek(week, Optional.empty(), Map.of(), List.of(), Set.of(), Set.of(),
                List.of());
    }

    HabitWeek withPlayed(PlayedWeek value) {
        return new HabitWeek(week, Optional.of(value), kickoff, moves, reserve, couldGoOnIr,
                clearings);
    }

    HabitWeek withKickoff(Map<String, PlayerHealth> value, Set<String> onReserve,
            Set<String> irEligible) {
        return new HabitWeek(week, played, value, moves, onReserve, irEligible, clearings);
    }

    HabitWeek withMoves(List<Move> value, List<Clearing> leagueClearings) {
        return new HabitWeek(week, played, kickoff, value, reserve, couldGoOnIr,
                leagueClearings);
    }

    /**
     * One FAAB claim that won, and the least it could have won for.
     *
     * @param bid the winning bid, in the league's own dollars
     * @param runnerUp the highest losing bid on the same player in the
     *        same waiver run; empty when nobody else bid
     * @param price the least that would have won: one dollar over the
     *        runner-up, or the league's minimum bid when nobody else bid,
     *        and never more than the bid itself
     */
    public record Clearing(String transactionId, String playerId, int bid,
            Optional<Integer> runnerUp, int price) {

        public Clearing {
            runnerUp = runnerUp == null ? Optional.empty() : runnerUp;
        }

        /** Returns the dollars paid above what would have won. */
        public int overpay() {
            return bid - price;
        }
    }

    /**
     * One transaction of the user's.
     *
     * @param type Sleeper's type: {@code waiver}, {@code free_agent},
     *        {@code trade} or {@code commissioner}
     * @param added the players it put on the user's roster, or, for a lost
     *        claim, the player he tried to claim
     * @param dropped the players it took off the user's roster
     * @param positions the position of every added or dropped player
     *        Otto knew, by player id
     * @param bid the FAAB bid in the league's own dollars; empty for a
     *        move that carried no bid
     * @param won false only for a FAAB claim another team won
     */
    public record Move(String transactionId, String type, Instant at, List<String> added,
            List<String> dropped, Map<String, String> positions, Optional<Integer> bid,
            boolean won) {

        public Move {
            added = List.copyOf(added);
            dropped = List.copyOf(dropped);
            positions = Map.copyOf(positions);
            bid = bid == null ? Optional.empty() : bid;
        }
    }
}
