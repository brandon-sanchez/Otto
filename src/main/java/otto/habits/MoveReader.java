package otto.habits;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.directory.DirectoryPlayer;
import otto.sleeper.SleeperAdapter;
import otto.snapshot.RosterSnapshot;

/**
 * Reads one week's transactions for the habit capture: the user's own
 * moves with their FAAB bids, his losing bids, and what every winning
 * bid in the league beat.
 */
@Component
class MoveReader {

    private final SleeperAdapter sleeper;

    MoveReader(SleeperAdapter sleeper, OttoProperties properties) {
        this.sleeper = sleeper.cachedWithin(properties.sleeperCadence());
    }

    /** One week's transactions: the user's own, and every FAAB claim the league won. */
    record WeekMoves(List<HabitWeek.Move> moves, List<HabitWeek.Clearing> clearings) {
    }

    /**
     * Returns one week's moves, or empty when Sleeper could not answer.
     *
     * <p>Side effects: Sleeper reads of the week's transactions and waiver
     * claims, answered from the cache while younger than the cadence.
     *
     * @param players the player directory, which names a position the
     *        roster leaves blank
     */
    Optional<WeekMoves> read(SleeperAdapter.League league, String season, int number,
            RosterSnapshot user, Map<String, DirectoryPlayer> players) {
        Optional<List<SleeperAdapter.LeagueTransaction>> transactions = HabitReads.read(
                "week " + number + " transactions", sleeper.transactions(season, number));
        if (transactions.isEmpty()) {
            return Optional.empty();
        }
        Optional<List<SleeperAdapter.WaiverClaim>> claims = HabitReads.read(
                "week " + number + " waiver claims", sleeper.waiverClaims(number));
        if (claims.isEmpty()) {
            return Optional.empty();
        }
        int rosterId = user.rosterId();
        Map<String, Integer> bids = new HashMap<>();
        claims.get().forEach(claim -> bids.put(claim.transactionId(), claim.bid()));
        List<HabitWeek.Move> moves = new ArrayList<>();
        for (SleeperAdapter.LeagueTransaction transaction : transactions.get()) {
            List<String> added = playersFor(transaction.adds(), rosterId);
            List<String> dropped = playersFor(transaction.drops(), rosterId);
            if (added.isEmpty() && dropped.isEmpty()) {
                continue;
            }
            moves.add(new HabitWeek.Move(transaction.transactionId(), transaction.type(),
                    transaction.statusUpdated(), added, dropped,
                    positions(added, dropped, user, players),
                    Optional.ofNullable(bids.get(transaction.transactionId())), true));
        }
        claims.get().stream()
                .filter(claim -> !claim.won() && claim.rosterId() == rosterId)
                .forEach(claim -> moves.add(new HabitWeek.Move(claim.transactionId(), "waiver",
                        claim.processed(), List.of(claim.playerId()), List.of(),
                        positions(List.of(claim.playerId()), List.of(), user, players),
                        Optional.of(claim.bid()), false)));
        return Optional.of(new WeekMoves(moves, clearings(claims.get(),
                league.rules().minimumBid().orElse(0))));
    }

    /**
     * Each won claim with the highest bid that lost to it: a losing bid on
     * the same player in the same run. A losing bid above the winner lost
     * for another reason, such as a full roster, so it never counts.
     */
    private static List<HabitWeek.Clearing> clearings(List<SleeperAdapter.WaiverClaim> claims,
            int minimumBid) {
        List<HabitWeek.Clearing> clearings = new ArrayList<>();
        for (SleeperAdapter.WaiverClaim won : claims) {
            if (!won.won()) {
                continue;
            }
            Optional<Integer> runnerUp = claims.stream()
                    .filter(lost -> !lost.won() && lost.playerId().equals(won.playerId())
                            && lost.processed().equals(won.processed())
                            && lost.bid() <= won.bid())
                    .map(SleeperAdapter.WaiverClaim::bid)
                    .max(Integer::compare);
            int price = Math.min(won.bid(), runnerUp.map(bid -> bid + 1).orElse(minimumBid));
            clearings.add(new HabitWeek.Clearing(won.transactionId(), won.playerId(),
                    won.bid(), runnerUp, price));
        }
        return clearings;
    }

    private static List<String> playersFor(Map<String, Integer> byPlayer, int rosterId) {
        return byPlayer.entrySet().stream()
                .filter(entry -> entry.getValue() == rosterId)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    private static Map<String, String> positions(List<String> added, List<String> dropped,
            RosterSnapshot user, Map<String, DirectoryPlayer> players) {
        Map<String, String> positions = new HashMap<>();
        for (List<String> group : List.of(added, dropped)) {
            group.forEach(playerId -> HabitReads.known(user.playerPositions().get(playerId),
                    players.get(playerId), DirectoryPlayer::position)
                    .ifPresent(position -> positions.put(playerId, position)));
        }
        return positions;
    }
}
