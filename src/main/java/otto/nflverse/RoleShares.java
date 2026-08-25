package otto.nflverse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RoleShares {

    public enum Kind {
        TARGET,
        SNAP
    }

    public record Game(int week, double share) {
    }

    public record Player(Kind kind, int newestWeek, List<Game> games) {
    }

    public record Reading(Player role, Optional<Game> latestSnap) {
    }

    private final Map<String, Player> rolesBySleeper;
    private final Map<String, Game> latestSnapsBySleeper;

    private RoleShares(Map<String, Player> rolesBySleeper,
            Map<String, Game> latestSnapsBySleeper) {
        this.rolesBySleeper = rolesBySleeper;
        this.latestSnapsBySleeper = latestSnapsBySleeper;
    }

    public static RoleShares from(NflverseStore store) {
        Optional<WeeklyStats> stats = store.weeklyStats();
        Optional<SnapCounts> snaps = store.snapCounts();
        Optional<PlayerIdMap> ids = store.playerIds();
        if (ids.isEmpty()) {
            return new RoleShares(Map.of(), Map.of());
        }
        Map<String, Player> roles = new HashMap<>();
        currentTargets(stats).ifPresent(feed -> addTargets(roles, feed, ids.get()));
        Map<String, Game> latestSnaps = new HashMap<>();
        currentSnaps(snaps).ifPresent(feed -> addSnaps(roles, latestSnaps, feed, ids.get()));
        return new RoleShares(Map.copyOf(roles), Map.copyOf(latestSnaps));
    }

    public Optional<Reading> of(String sleeperId) {
        Player role = rolesBySleeper.get(sleeperId);
        return role == null ? Optional.empty()
                : Optional.of(new Reading(role,
                        Optional.ofNullable(latestSnapsBySleeper.get(sleeperId))));
    }

    private static Optional<WeeklyStats> currentTargets(Optional<WeeklyStats> stats) {
        return stats.filter(feed -> !feed.priorSeasonFinal());
    }

    private static Optional<SnapCounts> currentSnaps(Optional<SnapCounts> snaps) {
        return snaps.filter(feed -> !feed.priorSeasonFinal());
    }

    private static void addTargets(Map<String, Player> roles, WeeklyStats stats, PlayerIdMap ids) {
        Map<String, List<Game>> games = new LinkedHashMap<>();
        int newestWeek = stats.rows().stream().mapToInt(WeeklyStats.StatLine::week).max().orElse(0);
        for (WeeklyStats.StatLine line : stats.rows()) {
            if (!("WR".equals(line.position()) || "TE".equals(line.position()))
                    || line.targetShare() == null) {
                continue;
            }
            Optional<String> sleeperId = ids.sleeperForGsis(line.gsisId());
            if (sleeperId.isPresent()) {
                games.computeIfAbsent(sleeperId.get(), ignored -> new ArrayList<>())
                        .add(new Game(line.week(), line.targetShare()));
            }
        }
        games.forEach((sleeperId, played) ->
                roles.put(sleeperId, player(Kind.TARGET, newestWeek, played)));
    }

    private static void addSnaps(Map<String, Player> roles, Map<String, Game> latestSnaps,
            SnapCounts snaps, PlayerIdMap ids) {
        Map<String, List<Game>> backGames = new LinkedHashMap<>();
        int newestWeek = snaps.newestWeek();
        for (SnapCounts.SnapLine line : snaps.rows()) {
            Optional<String> sleeperId = ids.sleeperForPfr(line.pfrId());
            if (sleeperId.isEmpty()) {
                continue;
            }
            Game game = new Game(line.week(), line.offensePct());
            if (line.week() == newestWeek) {
                latestSnaps.put(sleeperId.get(), game);
            }
            if ("RB".equals(line.position())) {
                backGames.computeIfAbsent(sleeperId.get(), ignored -> new ArrayList<>()).add(game);
            }
        }
        backGames.forEach((sleeperId, played) ->
                roles.put(sleeperId, player(Kind.SNAP, newestWeek, played)));
    }

    private static Player player(Kind kind, int newestWeek, List<Game> games) {
        games.sort(Comparator.comparingInt(Game::week));
        return new Player(kind, newestWeek, List.copyOf(games));
    }

}
