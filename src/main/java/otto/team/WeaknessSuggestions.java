package otto.team;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import otto.team.PositionProfile.Strength;

/**
 * Picks up to three moves for the user's thin positions: a free agent
 * who would climb his depth chart there, then a trade from a surplus
 * position with a manager whose roster mirrors his.
 */
public final class WeaknessSuggestions {

    /** The most suggestions one answer carries. */
    public static final int MOST = 3;

    private WeaknessSuggestions() {
    }

    /**
     * Returns the suggestions for the user's team, thinnest position
     * first.
     *
     * @param freeAgents every free agent with a per-game average
     * @param tradesOpen false once the trade deadline has passed, which
     *        leaves only waiver suggestions
     */
    public static List<Suggestion> of(LeagueProfile league, List<RatedPlayer> freeAgents,
            boolean tradesOpen) {
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
        List<Suggestion> suggestions = new ArrayList<>();
        thin.forEach(need -> waiver(need, freeAgents).ifPresent(suggestions::add));
        if (tradesOpen) {
            thin.stream()
                    .flatMap(need -> trades(league, user.get(), need))
                    .forEach(suggestions::add);
        }
        return suggestions.stream().limit(MOST).toList();
    }

    /**
     * The best free agent at a thin position when he beats the last
     * player of the user's depth chart there, which holds a starter and
     * a backup for every dedicated slot. A depth chart with an empty
     * place takes anyone.
     */
    private static Optional<Suggestion> waiver(PositionProfile need,
            List<RatedPlayer> freeAgents) {
        int depthChart = 2 * Math.max(1, need.dedicatedSlots());
        double toBeat = need.players().size() >= depthChart
                ? need.players().get(depthChart - 1).perGame()
                : 0;
        return freeAgents.stream()
                .filter(player -> player.position().equals(need.position()))
                .max(Comparator.comparingDouble(RatedPlayer::perGame))
                .filter(player -> player.perGame() > toBeat)
                .map(player -> new Suggestion.Waiver(need, player, 1 + (int) need.players()
                        .stream().filter(mine -> mine.perGame() > player.perGame()).count()));
    }

    private static Stream<Suggestion> trades(LeagueProfile league, TeamProfile user,
            PositionProfile need) {
        return user.positions().values().stream()
                .filter(position -> position.strength() == Strength.SURPLUS)
                .flatMap(surplus -> {
                    List<String> partners = league.teams().stream()
                            .filter(team -> !team.userTeam())
                            .filter(team -> team.at(need.position())
                                    .filter(theirs -> theirs.depthRank() < need.depthRank())
                                    .isPresent())
                            .filter(team -> team.at(surplus.position())
                                    .filter(theirs -> theirs.strength() == Strength.THIN)
                                    .isPresent())
                            .map(TeamProfile::manager)
                            .toList();
                    if (partners.isEmpty()) {
                        return Stream.empty();
                    }
                    List<RatedPlayer> give = surplus.players().subList(surplus.spots(),
                            surplus.startable());
                    return Stream.of(new Suggestion.Trade(surplus, need, give, partners));
                });
    }
}
