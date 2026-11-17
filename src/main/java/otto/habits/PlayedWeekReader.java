package otto.habits;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.directory.DirectoryPlayer;
import otto.lineup.GameWeek;
import otto.lineup.LeagueScoring;
import otto.lineup.LineupOptimizer;
import otto.lineup.PlayerPositions;
import otto.lineup.Slot;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SleeperAdapter.StarterLine;
import otto.snapshot.RosterSnapshot;

/**
 * Reads one scored week for the habit capture: the user's lineup with
 * every player's points, the best lineup in hindsight, the teams on bye,
 * the best free-agent kicker and defense, and what the players he dropped
 * scored while on no roster.
 */
@Component
class PlayedWeekReader {

    private final SleeperAdapter sleeper;
    private final LineupOptimizer optimizer;

    PlayedWeekReader(SleeperAdapter sleeper, LineupOptimizer optimizer,
            OttoProperties properties) {
        this.sleeper = sleeper.cachedWithin(properties.sleeperCadence());
        this.optimizer = optimizer;
    }

    /**
     * One scored week to read.
     *
     * @param letGo the players the user dropped in earlier weeks, whose
     *        scores off any roster the drop habit compares
     */
    record Week(String season, int number, LeagueScoring scoring, Set<String> letGo) {
    }

    /**
     * Returns the user's week, or empty when Sleeper could not serve its
     * matchups or the user's line in them.
     *
     * <p>Side effects: Sleeper reads of the week's matchups and games,
     * plus its projections and stats in a league that starts a kicker or
     * defense or once the user has dropped someone; each is answered from
     * the cache while younger than the cadence.
     *
     * @param players the player directory, which names a position or team
     *        the roster leaves blank
     */
    Optional<PlayedWeek> read(SleeperAdapter.League league, RosterSnapshot user, Week week,
            Map<String, DirectoryPlayer> players) {
        Optional<List<StarterLine>> lines = HabitReads.read(
                "week " + week.number() + " matchups", sleeper.matchups(week.number()));
        Optional<StarterLine> line = lines.stream()
                .flatMap(List::stream)
                .filter(candidate -> candidate.rosterId() == user.rosterId())
                .findFirst();
        if (line.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Double> leaguePoints = new HashMap<>();
        lines.get().forEach(other -> leaguePoints.putAll(other.playerPoints()));
        Map<String, Double> points = new LinkedHashMap<>(line.get().playerPoints());
        for (int slot = 0; slot < line.get().starters().size(); slot++) {
            String starter = line.get().starters().get(slot);
            if (!StarterLine.EMPTY_SLOT.equals(starter)) {
                points.putIfAbsent(starter, line.get().points().get(slot));
            }
        }
        List<String> slots = league.rosterPositions().stream()
                .filter(position -> !"BN".equals(position))
                .toList();
        Map<String, Map<String, Double>> stats = needsStats(slots, week, leaguePoints)
                ? stats(week)
                : Map.of();
        Map<String, String> positions = new HashMap<>();
        Map<String, String> teams = new HashMap<>();
        for (String playerId : points.keySet()) {
            String listed = HabitReads.known(user.playerPositions().get(playerId),
                    players.get(playerId), DirectoryPlayer::position).orElse(null);
            PlayerPositions.of(playerId, listed, stats.get(playerId))
                    .ifPresent(value -> positions.put(playerId, value));
            HabitReads.known(user.playerTeams().get(playerId), players.get(playerId),
                    DirectoryPlayer::team).ifPresent(value -> teams.put(playerId, value));
        }
        // Sleeper only lets a kicker or defense start in its own slot, so
        // one with no kicks in his stat line is still placed.
        List<String> starters = line.get().starters();
        for (int slot = 0; slot < Math.min(slots.size(), starters.size()); slot++) {
            if (PlayedWeek.STREAMED.contains(slots.get(slot))
                    && !StarterLine.EMPTY_SLOT.equals(starters.get(slot))) {
                positions.putIfAbsent(starters.get(slot), slots.get(slot));
            }
        }
        return Optional.of(new PlayedWeek(slots, line.get().starters(), points, leaguePoints,
                positions, teams, optimal(slots, line.get().starters(), points, positions),
                byes(week, teams), freeAgents(slots, week, leaguePoints, stats),
                elsewhere(week, leaguePoints, players, stats)));
    }

    /**
     * Returns the week's games, or empty when Sleeper could not serve them
     * or listed none.
     *
     * <p>Side effects: a Sleeper read, answered from the cache while
     * younger than the cadence.
     */
    Optional<GameWeek> games(String season, int week) {
        return HabitReads.read("week " + week + " games", sleeper.games(season, week))
                .filter(games -> !games.isEmpty())
                .map(GameWeek::of);
    }

    private static boolean needsStats(List<String> slots, Week week,
            Map<String, Double> leaguePoints) {
        return slots.stream().anyMatch(PlayedWeek.STREAMED::contains)
                || week.letGo().stream().anyMatch(playerId -> !leaguePoints.containsKey(playerId));
    }

    private Map<String, Map<String, Double>> stats(Week week) {
        return HabitReads.read("week " + week.number() + " stats",
                sleeper.stats(week.season(), week.number())).orElse(Map.of());
    }

    private Set<String> byes(Week week, Map<String, String> teams) {
        return games(week.season(), week.number())
                .map(gameWeek -> teams.values().stream()
                        .filter(gameWeek::onBye)
                        .collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    /**
     * For each K and DEF slot the league starts, the free agent projected
     * highest that week: a player at the position on no roster.
     */
    private Map<String, PlayedWeek.FreeAgent> freeAgents(List<String> slots, Week week,
            Map<String, Double> leaguePoints, Map<String, Map<String, Double>> stats) {
        Set<String> positions = slots.stream()
                .filter(PlayedWeek.STREAMED::contains)
                .collect(Collectors.toSet());
        if (positions.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Double>> projections = HabitReads.read(
                "week " + week.number() + " projections",
                sleeper.projections(week.season(), week.number())).orElse(Map.of());
        Map<String, PlayedWeek.FreeAgent> best = new HashMap<>();
        projections.forEach((playerId, line) -> {
            Optional<String> position = PlayerPositions.kickerOrDefense(playerId, line);
            if (position.isEmpty() || !positions.contains(position.get())
                    || leaguePoints.containsKey(playerId)) {
                return;
            }
            week.scoring().price(line, position.get()).ifPresent(projected -> {
                PlayedWeek.FreeAgent leader = best.get(position.get());
                if (leader == null || projected > leader.projected()) {
                    double scored = week.scoring().price(stats.get(playerId), position.get())
                            .orElse(0.0);
                    best.put(position.get(),
                            new PlayedWeek.FreeAgent(playerId, projected, scored));
                }
            });
        });
        return best;
    }

    /** What the players the user let go scored that week while on no roster. */
    private static Map<String, Double> elsewhere(Week week, Map<String, Double> leaguePoints,
            Map<String, DirectoryPlayer> players, Map<String, Map<String, Double>> stats) {
        Map<String, Double> scored = new HashMap<>();
        for (String playerId : week.letGo()) {
            DirectoryPlayer player = players.get(playerId);
            if (leaguePoints.containsKey(playerId) || player == null) {
                continue;
            }
            week.scoring().price(stats.get(playerId), player.position())
                    .ifPresent(points -> scored.put(playerId, points));
        }
        return scored;
    }

    private List<String> optimal(List<String> slotNames, List<String> starters,
            Map<String, Double> points, Map<String, String> positions) {
        List<Slot> all = Slot.startingSlots(slotNames);
        List<Integer> open = new ArrayList<>();
        Map<String, Double> movable = new HashMap<>(points);
        for (int index = 0; index < all.size(); index++) {
            if (all.get(index).modelledExactly()) {
                open.add(index);
            } else if (index < starters.size()) {
                movable.remove(starters.get(index));
            }
        }
        movable.keySet().removeIf(playerId -> !positions.containsKey(playerId));
        Map<Integer, String> assigned = optimizer.assign(open.stream().map(all::get).toList(),
                movable, positions, starters);
        List<String> optimal = new ArrayList<>();
        for (int index = 0; index < all.size(); index++) {
            int openIndex = open.indexOf(index);
            if (openIndex < 0) {
                optimal.add(index < starters.size() ? starters.get(index) : StarterLine.EMPTY_SLOT);
            } else {
                optimal.add(assigned.getOrDefault(openIndex, StarterLine.EMPTY_SLOT));
            }
        }
        return optimal;
    }
}
