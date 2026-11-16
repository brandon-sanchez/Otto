package otto.nflverse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Each skill player's share of his offence game by game this season, keyed
 * by Sleeper id: target share for receivers, tight ends and running
 * backs, and snap share for running backs. A back is read both ways
 * because a growing receiving role shows in his targets before it shows
 * in his snaps. Last season's shares are never read: they say nothing
 * about a role now.
 */
public final class RoleShares {

    private static final Set<String> TARGET_POSITIONS = Set.of("WR", "TE", "RB");

    /** Which share a {@link Player} holds. */
    public enum Kind {
        TARGET,
        SNAP
    }

    /** @param share from 0 to 1 */
    public record Game(int week, double share) {
    }

    /**
     * One kind of share across a player's games.
     *
     * @param newestWeek the newest week the feed holds for anyone, so a
     *        player whose last game is older is known not to have played since
     * @param games oldest first
     */
    public record Player(Kind kind, int newestWeek, List<Game> games) {
    }

    /**
     * What is known about one player.
     *
     * @param targets his target shares, or empty when the stats have none
     * @param snaps his snap shares as a running back, or empty for any
     *        other position or without a snap row
     * @param latestSnap his snap share in the newest week, at any position
     */
    public record Reading(Optional<Player> targets, Optional<Player> snaps,
            Optional<Game> latestSnap) {
    }

    private final Map<String, Player> targetsBySleeper;
    private final Map<String, Player> snapsBySleeper;
    private final Map<String, Game> latestSnapsBySleeper;

    private RoleShares(Map<String, Player> targetsBySleeper, Map<String, Player> snapsBySleeper,
            Map<String, Game> latestSnapsBySleeper) {
        this.targetsBySleeper = targetsBySleeper;
        this.snapsBySleeper = snapsBySleeper;
        this.latestSnapsBySleeper = latestSnapsBySleeper;
    }

    /** Reads this season's shares from the store; without an id map nobody can be read. */
    public static RoleShares from(NflverseStore store) {
        Optional<PlayerIdMap> ids = store.playerIds();
        if (ids.isEmpty()) {
            return new RoleShares(Map.of(), Map.of(), Map.of());
        }
        Map<String, Player> targets = store.weeklyStats()
                .filter(feed -> !feed.priorSeasonFinal())
                .map(feed -> targets(feed, ids.get()))
                .orElse(Map.of());
        Map<String, Player> snaps = new HashMap<>();
        Map<String, Game> latestSnaps = new HashMap<>();
        store.snapCounts()
                .filter(feed -> !feed.priorSeasonFinal())
                .ifPresent(feed -> addSnaps(snaps, latestSnaps, feed, ids.get()));
        return new RoleShares(targets, Map.copyOf(snaps), Map.copyOf(latestSnaps));
    }

    /** Returns what is known about one player, or empty when neither feed lists him. */
    public Optional<Reading> of(String sleeperId) {
        Optional<Player> targets = Optional.ofNullable(targetsBySleeper.get(sleeperId));
        Optional<Player> snaps = Optional.ofNullable(snapsBySleeper.get(sleeperId));
        if (targets.isEmpty() && snaps.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Reading(targets, snaps,
                Optional.ofNullable(latestSnapsBySleeper.get(sleeperId))));
    }

    private static Map<String, Player> targets(WeeklyStats stats, PlayerIdMap ids) {
        Map<String, List<Game>> games = new LinkedHashMap<>();
        int newestWeek = stats.rows().stream().mapToInt(WeeklyStats.StatLine::week).max().orElse(0);
        for (WeeklyStats.StatLine line : stats.rows()) {
            Double share = line.usage().targetShare();
            if (!TARGET_POSITIONS.contains(line.position()) || share == null) {
                continue;
            }
            ids.sleeperForGsis(line.gsisId()).ifPresent(sleeperId -> games
                    .computeIfAbsent(sleeperId, ignored -> new ArrayList<>())
                    .add(new Game(line.week(), share)));
        }
        Map<String, Player> roles = new HashMap<>();
        games.forEach((sleeperId, played) ->
                roles.put(sleeperId, player(Kind.TARGET, newestWeek, played)));
        return Map.copyOf(roles);
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
