package otto.habits;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 */
public record HabitWeek(int week, Optional<PlayedWeek> played,
        Map<String, PlayerHealth> kickoff, List<Move> moves) {

    public HabitWeek {
        played = played == null ? Optional.empty() : played;
        kickoff = kickoff == null ? Map.of() : Map.copyOf(kickoff);
        moves = moves == null ? List.of() : List.copyOf(moves);
    }

    static HabitWeek empty(int week) {
        return new HabitWeek(week, Optional.empty(), Map.of(), List.of());
    }

    HabitWeek withPlayed(PlayedWeek value) {
        return new HabitWeek(week, Optional.of(value), kickoff, moves);
    }

    HabitWeek withKickoff(Map<String, PlayerHealth> value) {
        return new HabitWeek(week, played, value, moves);
    }

    HabitWeek withMoves(List<Move> value) {
        return new HabitWeek(week, played, kickoff, value);
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
