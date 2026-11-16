package otto.team;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

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
     * Returns up to {@link #MOST} waiver suggestions for the user's team,
     * thinnest position first.
     *
     * @param freeAgents every free agent with a per-game average
     */
    public static List<WaiverSuggestion> of(LeagueProfile league,
            List<RatedPlayer> freeAgents) {
        Optional<TeamProfile> user = league.user();
        if (user.isEmpty()) {
            return List.of();
        }
        List<PositionProfile> thin = user.get().positions().values().stream()
                .filter(position -> position.strength() == Strength.THIN)
                .sorted(Comparator.comparingInt((PositionProfile position) ->
                        position.starterRank() + position.depthRank()).reversed()
                        .thenComparing(position ->
                                LeagueProfile.POSITIONS.indexOf(position.position())))
                .toList();
        List<WaiverSuggestion> suggestions = new ArrayList<>();
        thin.forEach(need -> waiver(need, freeAgents).ifPresent(suggestions::add));
        return suggestions.stream().limit(MOST).toList();
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
                .map(player -> new WaiverSuggestion(need, player, 1 + (int) need.players()
                        .stream().filter(mine -> mine.perGame() > player.perGame()).count()));
    }
}
