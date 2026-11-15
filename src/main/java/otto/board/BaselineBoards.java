package otto.board;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.ask.LeagueWeek;
import otto.directory.DirectoryPlayer;
import otto.directory.PlayerDirectory;
import otto.lineup.ProjectionTable;
import otto.lineup.Slot;
import otto.needs.RosterNeeds;
import otto.needs.RosterNeedsService;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;
import otto.waivers.WaiverAlertService;
import otto.waivers.WaiverBoard;
import otto.waivers.WaiverQuery;

/**
 * Builds the baselines the waiver board is judged against for one
 * claim round: the scored board and the simple strategies in
 * {@link BoardStrategy}. Each proposes as many pickups as the board
 * shows.
 *
 * <p>A baseline ranks on projections alone, so a free agent with no
 * projection this week is left out of it rather than ranked as zero.
 */
@Component
class BaselineBoards {

    private static final List<String> POSITIONS = WaiverQuery.ALL_POSITIONS;

    private final WaiverAlertService scoredBoard;
    private final RosterNeedsService needs;
    private final SleeperAdapter sleeper;

    BaselineBoards(WaiverAlertService scoredBoard, RosterNeedsService needs,
            SleeperAdapter sleeper, OttoProperties properties) {
        this.scoredBoard = scoredBoard;
        this.needs = needs;
        this.sleeper = sleeper.cachedWithin(properties.sleeperCadence());
    }

    /**
     * Every baseline's pickups for one claim round.
     *
     * @param unavailable the reason for each baseline that could not be
     *        built
     */
    record Built(Map<BoardStrategy, List<ComparisonRound.Pick>> picks,
            Map<BoardStrategy, String> unavailable) {
    }

    /**
     * Side effects: the scored board's reads, the roster needs' read and
     * store of completed weeks, and a Sleeper trending read answered
     * from the cache while it is younger than the cadence interval.
     */
    Built build(LeagueWeek league, PlayerDirectory directory, List<DirectoryPlayer> freeAgents,
            Instant now) {
        Set<String> free = freeAgents.stream().map(DirectoryPlayer::playerId)
                .collect(Collectors.toSet());
        Function<String, ComparisonRound.Pick> pick = playerId ->
                pickOf(playerId, directory, free, league.week().startingSlots());
        Map<BoardStrategy, List<ComparisonRound.Pick>> picks = new EnumMap<>(BoardStrategy.class);
        Map<BoardStrategy, String> unavailable = new EnumMap<>(BoardStrategy.class);

        switch (scoredBoard.scoredBoard(league, now)) {
            case SourceResult.Ok<WaiverBoard> ok -> picks.put(BoardStrategy.SCORED_BOARD,
                    ok.value().candidates().stream()
                            .map(candidate -> pick.apply(candidate.playerId()))
                            .toList());
            case SourceResult.Unavailable<WaiverBoard> down ->
                    unavailable.put(BoardStrategy.SCORED_BOARD, down.reason());
        }

        picks.put(BoardStrategy.NO_PICKUP, List.of());

        Optional<ProjectionTable> projections = league.week().projections();
        if (projections.isEmpty()) {
            String reason = "there are no projections this week to rank free agents by";
            unavailable.put(BoardStrategy.NEED_FIRST, reason);
            unavailable.put(BoardStrategy.PLAYER_RANK, reason);
        } else {
            Map<String, Double> projected = projected(freeAgents, projections.get());
            Comparator<DirectoryPlayer> bestProjected = Comparator.comparingDouble(
                    (DirectoryPlayer player) -> projected.get(player.playerId())).reversed();
            picks.put(BoardStrategy.PLAYER_RANK,
                    top(freeAgents, projected, bestProjected, pick));
            switch (needs.assess(league)) {
                case SourceResult.Ok<RosterNeeds> ok -> {
                    List<String> order = needOrder(ok.value());
                    picks.put(BoardStrategy.NEED_FIRST, top(freeAgents, projected,
                            Comparator.comparingInt((DirectoryPlayer player) ->
                                    order.indexOf(player.position())).thenComparing(bestProjected),
                            pick));
                }
                case SourceResult.Unavailable<RosterNeeds> down ->
                        unavailable.put(BoardStrategy.NEED_FIRST, down.reason());
            }
        }

        switch (sleeper.trendingAdds(SleeperAdapter.TRENDING_LOOKBACK_HOURS,
                SleeperAdapter.TRENDING_LIMIT)) {
            case SourceResult.Ok<List<SleeperAdapter.TrendingPlayer>> ok ->
                    picks.put(BoardStrategy.TOP_TRENDING, ok.value().stream()
                            .map(SleeperAdapter.TrendingPlayer::playerId)
                            .filter(directory.players()::containsKey)
                            .distinct()
                            .limit(BoardMessage.SHOWN)
                            .map(pick)
                            .toList());
            case SourceResult.Unavailable<List<SleeperAdapter.TrendingPlayer>> down ->
                    unavailable.put(BoardStrategy.TOP_TRENDING, down.reason());
        }
        return new Built(picks, unavailable);
    }

    private static Map<String, Double> projected(List<DirectoryPlayer> freeAgents,
            ProjectionTable projections) {
        return freeAgents.stream()
                .flatMap(player -> projections.points(player.playerId(), player.position())
                        .map(points -> Map.entry(player.playerId(), points)).stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static List<ComparisonRound.Pick> top(List<DirectoryPlayer> freeAgents,
            Map<String, Double> projected, Comparator<DirectoryPlayer> order,
            Function<String, ComparisonRound.Pick> pick) {
        return freeAgents.stream()
                .filter(player -> projected.containsKey(player.playerId()))
                .sorted(order)
                .limit(BoardMessage.SHOWN)
                .map(player -> pick.apply(player.playerId()))
                .toList();
    }

    /**
     * The positions from most needed to least: those that would fill an
     * open slot, then each single-position slot's position from the one
     * ranked lowest against the league, then the rest.
     */
    private static List<String> needOrder(RosterNeeds needs) {
        Set<String> order = new LinkedHashSet<>();
        POSITIONS.stream().filter(needs.coverage().positionsThatFill()::contains)
                .forEach(order::add);
        needs.weakness().stream()
                .filter(standing -> POSITIONS.contains(standing.slot()))
                .filter(standing -> standing.rank().isPresent())
                .sorted(Comparator.comparingDouble((RosterNeeds.SlotStanding standing) ->
                        (double) standing.rank().orElseThrow() / standing.rankedTeams())
                        .reversed())
                .forEach(standing -> order.add(standing.slot()));
        order.addAll(POSITIONS);
        return new ArrayList<>(order);
    }

    private static ComparisonRound.Pick pickOf(String playerId, PlayerDirectory directory,
            Set<String> free, List<Slot> slots) {
        DirectoryPlayer player = directory.players().get(playerId);
        String position = player == null ? "" : player.position();
        return new ComparisonRound.Pick(playerId, player == null ? playerId : player.fullName(),
                position, free.contains(playerId),
                slots.stream().anyMatch(slot -> slot.accepts(position)));
    }
}
