package otto.team;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import otto.advice.Ordinal;
import otto.team.PositionProfile.Strength;

/**
 * Picks the free agents who would shore up the user's thin positions:
 * at most one per position, each climbing his depth chart there.
 */
public final class WeaknessSuggestions {

    /** The most suggestions one answer carries. */
    public static final int MOST = 3;

    private WeaknessSuggestions() {
    }

    /**
     * Returns up to {@link #MOST} waiver suggestions for the user's team:
     * the positions in {@code depthFirst} first, then his thin positions,
     * thinnest first.
     *
     * @param freeAgents every free agent with a per-game average
     * @param depthFirst positions whose depth is weighed as a need even
     *        when they are not thin, most pressing first; a position in
     *        surplus is never one
     */
    public static List<WaiverSuggestion> of(LeagueProfile league,
            List<RatedPlayer> freeAgents, List<String> depthFirst) {
        Optional<TeamProfile> user = league.user();
        if (user.isEmpty()) {
            return List.of();
        }
        Stream<PositionProfile> weighed = depthFirst.stream()
                .flatMap(position -> user.get().at(position).stream())
                .filter(position -> position.strength() != Strength.SURPLUS);
        Stream<PositionProfile> thin = user.get().positions().values().stream()
                .filter(position -> position.strength() == Strength.THIN)
                .sorted(Comparator.comparingInt((PositionProfile position) ->
                        position.starterRank() + position.depthRank()).reversed()
                        .thenComparing(position ->
                                LeagueProfile.POSITIONS.indexOf(position.position())));
        return Stream.concat(weighed, thin)
                .distinct()
                .flatMap(need -> waiver(need, freeAgents).stream())
                .limit(MOST)
                .toList();
    }

    /**
     * The best free agent at a thin position when he beats the last
     * player of the user's depth chart there, which holds a starter and
     * a backup for every dedicated slot. A depth chart with an empty
     * place takes anyone.
     */
    private static Optional<WaiverSuggestion> waiver(PositionProfile need,
            List<RatedPlayer> freeAgents) {
        int depthChart = 2 * Math.max(1, need.dedicatedSlots());
        double toBeat = need.players().size() >= depthChart
                ? need.players().get(depthChart - 1).perGame()
                : 0;
        return freeAgents.stream()
                .filter(player -> player.position().equals(need.position()))
                .max(Comparator.comparingDouble(RatedPlayer::perGame))
                .filter(player -> player.perGame() > toBeat)
                .map(player -> new WaiverSuggestion(need, player, Ordinal.rankAmong(
                        player.perGame(),
                        need.players().stream().map(RatedPlayer::perGame).toList())));
    }
}
