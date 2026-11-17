package otto.team;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import otto.ask.LeagueWeek;
import otto.ceiling.Ceiling;
import otto.directory.PlayerHealth;
import otto.lineup.LeagueScoring;
import otto.lineup.LineupOptimizer;
import otto.lineup.Slot;
import otto.sleeper.SleeperAdapter;
import otto.snapshot.RosterSnapshot;
import otto.team.PositionProfile.Strength;
import otto.trade.RestOfSeason;

/**
 * Profiles every team in a league position by position, so the user's
 * roster can be read against the rest: starters, depth and how many
 * startable players each position holds.
 *
 * <p>Every number comes from one per-game rest-of-season average per
 * player, which suits a question about the whole season better than one
 * week's projection does. The bench counts. An IR or taxi player counts
 * only when the active roster has a spot to bring him up into and his
 * designation does not rule him out, because otherwise his team cannot
 * use him.
 */
@Component
public class TeamProfiler {

    private static final String QUARTERBACK = "QB";

    private final LineupOptimizer optimizer;

    public TeamProfiler(LineupOptimizer optimizer) {
        this.optimizer = optimizer;
    }

    /**
     * Profiles every team that holds a player.
     *
     * @param perGame each player's per-game rest-of-season average; a
     *        player missing from it has no projection left and is left
     *        out rather than read as 0
     */
    public LeagueProfile profile(LeagueWeek league, Map<String, Double> perGame) {
        List<Slot> slots = league.week().startingSlots().stream()
                .filter(Slot::modelledExactly)
                .toList();
        LeagueScoring scoring = league.week().scoring();
        List<Team> teams = league.rosters().stream()
                .filter(roster -> !roster.players().isEmpty())
                .map(roster -> team(roster, league.league(), perGame, slots, scoring))
                .toList();
        List<Double> booms = teams.stream().map(Team::boomStarters).toList();
        Map<Integer, Map<String, PositionProfile>> positions = positions(teams, slots);
        return new LeagueProfile(teams.stream()
                .map(team -> new TeamProfile(team.roster().rosterId(), team.roster().manager(),
                        team.roster().userRoster(), positions.get(team.roster().rosterId()),
                        team.lineup(), team.boomStarters(), rank(team.boomStarters(), booms)))
                .toList());
    }

    /**
     * Returns each priced player's per-game average from a rest-of-season
     * total: the points over the weeks priced, byes left out of both.
     */
    public static Map<String, Double> perGame(RestOfSeason restOfSeason) {
        Map<String, Double> averages = new LinkedHashMap<>();
        restOfSeason.points().forEach((playerId, points) -> {
            int games = restOfSeason.weeksPricedFor(playerId)
                    - restOfSeason.byesFor(playerId).size();
            if (games > 0) {
                averages.put(playerId, points / games);
            }
        });
        return averages;
    }

    private record Team(RosterSnapshot roster, List<RatedPlayer> players,
            List<RatedPlayer> lineup) {

        List<RatedPlayer> at(String position) {
            return players.stream().filter(player -> player.position().equals(position)).toList();
        }

        double boomStarters() {
            return lineup.stream()
                    .flatMap(player -> player.boomChance().stream())
                    .mapToDouble(Double::doubleValue)
                    .sum();
        }
    }

    private Team team(RosterSnapshot roster, SleeperAdapter.League league,
            Map<String, Double> perGame, List<Slot> slots, LeagueScoring scoring) {
        Map<String, String> positions = roster.playerPositions();
        Map<String, Double> points = new LinkedHashMap<>();
        usable(roster, league, perGame).stream()
                .filter(playerId -> LeagueProfile.POSITIONS.contains(positions.get(playerId)))
                .filter(perGame::containsKey)
                .forEach(playerId -> points.put(playerId, perGame.get(playerId)));
        Set<String> starters = Set.copyOf(
                optimizer.assign(slots, points, positions, Set.of()).values());
        List<RatedPlayer> players = points.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(entry -> rated(roster, entry.getKey(), entry.getValue(),
                        starters.contains(entry.getKey()), scoring))
                .toList();
        return new Team(roster, players, players.stream().filter(RatedPlayer::starter).toList());
    }

    private static RatedPlayer rated(RosterSnapshot roster, String playerId, double perGame,
            boolean starter, LeagueScoring scoring) {
        String position = roster.playerPositions().get(playerId);
        return new RatedPlayer(playerId, roster.playerNames().getOrDefault(playerId, playerId),
                position, perGame, starter,
                Ceiling.estimate(position, perGame, scoring).map(Ceiling::boomChance));
    }

    /**
     * The active roster, plus as many IR and taxi players as the active
     * roster has open spots for, best first, leaving out any whose
     * designation rules him out.
     */
    private static List<String> usable(RosterSnapshot roster, SleeperAdapter.League league,
            Map<String, Double> perGame) {
        Set<String> held = new LinkedHashSet<>(roster.reserve());
        held.addAll(roster.taxi());
        long open = Math.max(0, league.activeRosterSpots() - roster.activePlayerCount());
        Stream<String> active = roster.players().stream().filter(id -> !held.contains(id));
        Stream<String> activatable = held.stream()
                .filter(id -> !roster.playerHealth().getOrDefault(id, PlayerHealth.ACTIVE)
                        .rulesOutPlaying())
                .filter(perGame::containsKey)
                .sorted(Comparator.comparing(perGame::get).reversed())
                .limit(open);
        return Stream.concat(active, activatable).distinct().toList();
    }

    private static Map<Integer, Map<String, PositionProfile>> positions(List<Team> teams,
            List<Slot> slots) {
        Map<Integer, Map<String, PositionProfile>> byTeam = new LinkedHashMap<>();
        teams.forEach(team -> byTeam.put(team.roster().rosterId(), new LinkedHashMap<>()));
        for (String position : LeagueProfile.POSITIONS) {
            if (slots.stream().noneMatch(slot -> slot.accepts(position))) {
                continue;
            }
            int dedicated = (int) slots.stream()
                    .filter(slot -> slot.eligible().equals(Set.of(position)))
                    .count();
            int spots = (int) slots.stream()
                    .filter(slot -> slot.accepts(position))
                    .filter(slot -> position.equals(QUARTERBACK) || !slot.accepts(QUARTERBACK))
                    .count();
            int width = Math.max(1, dedicated);
            List<Double> starterAverages = teams.stream()
                    .map(team -> average(team.at(position), 0, width)).toList();
            List<Double> depthAverages = teams.stream()
                    .map(team -> average(team.at(position), width, width)).toList();
            double line = startableLine(teams, position);
            for (int index = 0; index < teams.size(); index++) {
                Team team = teams.get(index);
                List<RatedPlayer> players = team.at(position);
                int startable = (int) players.stream()
                        .filter(player -> player.perGame() >= line).count();
                int starterRank = rank(starterAverages.get(index), starterAverages);
                int depthRank = rank(depthAverages.get(index), depthAverages);
                byTeam.get(team.roster().rosterId()).put(position, new PositionProfile(position,
                        dedicated, spots, players, starterAverages.get(index), starterRank,
                        depthAverages.get(index), depthRank, startable, line, teams.size(),
                        strength(startable, spots, starterRank, depthRank, teams.size())));
            }
        }
        return byTeam;
    }

    /**
     * The per-game average of the last rostered player at a position who
     * still makes a best lineup, counting every team's starters there:
     * with twelve RBs starting across the league, the twelfth-best
     * rostered RB sets the line.
     */
    private static double startableLine(List<Team> teams, String position) {
        long starting = teams.stream()
                .flatMap(team -> team.lineup().stream())
                .filter(player -> player.position().equals(position))
                .count();
        List<Double> pooled = teams.stream()
                .flatMap(team -> team.at(position).stream())
                .map(RatedPlayer::perGame)
                .sorted(Comparator.reverseOrder())
                .toList();
        if (starting == 0 || pooled.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        return pooled.get((int) Math.min(starting, pooled.size()) - 1);
    }

    private static Strength strength(int startable, int spots, int starterRank, int depthRank,
            int teams) {
        if (startable > spots) {
            return Strength.SURPLUS;
        }
        if (teams < 2) {
            return Strength.MIDDLE;
        }
        int third = Math.max(1, teams / 3);
        if (starterRank > teams - third || depthRank > teams - third) {
            return Strength.THIN;
        }
        return starterRank <= third ? Strength.STRONG : Strength.MIDDLE;
    }

    private static double average(List<RatedPlayer> players, int from, int count) {
        double total = players.stream().skip(from).limit(count)
                .mapToDouble(RatedPlayer::perGame).sum();
        return total / count;
    }

    private static int rank(double value, List<Double> values) {
        return 1 + (int) values.stream().filter(other -> other > value).count();
    }
}
