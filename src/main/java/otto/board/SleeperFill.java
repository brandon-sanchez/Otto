package otto.board;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import otto.directory.DirectoryPlayer;
import otto.nflverse.FeedId;
import otto.nflverse.PlayerUsage;
import otto.nflverse.WeeklyStats;
import otto.sleeper.PlayedWeekStats;

/**
 * Sleeper's stat lines for the latest completed week, standing in for
 * the teams whose nflverse weekly stats or snap counts had not arrived
 * when the board was built. nflverse stays the source for every team it
 * holds: a team is filled only when its nflverse week is short and
 * Sleeper holds that team's game.
 *
 * <p>Sleeper leaves a zero stat out of a line, so a played line counts
 * every stat it omits as zero. Only Sleeper's snaps, targets, carries
 * and box score are read; every other part of a game, such as air-yards
 * share, red-zone and end-zone work or expected points, stays unknown.
 *
 * <p>A player is matched to a filled team by his Player Directory team,
 * since Sleeper's lines carry none. A player traded since the filled
 * week is matched to his new team, so his old team's game fills
 * nothing of his.
 *
 * @param week the week filled; 0 when nothing is
 * @param teamsByFeed the teams each required feed takes from Sleeper
 * @param opponents each team's opponent that week
 */
record SleeperFill(int week, Map<FeedId, Set<String>> teamsByFeed,
        Map<String, String> opponents, PlayedWeekStats stats) {

    /** The evidence source every value from Sleeper is labelled with. */
    static final String SOURCE = "sleeper:stats";

    /** The fill of a week nflverse holds in full. */
    static final SleeperFill NONE = new SleeperFill(0, Map.of(), Map.of(),
            PlayedWeekStats.EMPTY);

    SleeperFill {
        Map<FeedId, Set<String>> inFeedOrder = new EnumMap<>(FeedId.class);
        teamsByFeed.forEach((feed, teams) -> inFeedOrder.put(feed, Set.copyOf(teams)));
        teamsByFeed = Collections.unmodifiableMap(inFeedOrder);
        opponents = Map.copyOf(opponents);
    }

    /** Returns the feeds Sleeper fills, in feed order. */
    Set<FeedId> feeds() {
        return teamsByFeed.keySet();
    }

    /**
     * Returns the player's stats for the filled week in the weekly
     * stats' vocabulary, empty unless his team's weekly stats are filled
     * and he played.
     */
    Optional<Map<String, Double>> stats(DirectoryPlayer player) {
        if (!fills(FeedId.WEEKLY_STATS, player)) {
            return Optional.empty();
        }
        return stats.played(player.playerId()).map(line -> WeeklyStats.STAT_KEYS.stream()
                .collect(Collectors.toMap(key -> key, key -> line.getOrDefault(key, 0.0))));
    }

    /** Returns the team the player's filled game was against, or null when unknown. */
    String opponent(DirectoryPlayer player) {
        return opponents.get(player.team());
    }

    /**
     * Returns the player's offensive snaps, empty unless his team's snap
     * counts are filled and his line counts his and his team's.
     */
    Optional<PlayerUsage.Snaps> snaps(DirectoryPlayer player) {
        if (!fills(FeedId.SNAP_COUNTS, player)) {
            return Optional.empty();
        }
        return stats.snaps(player.playerId())
                .map(snaps -> new PlayerUsage.Snaps(snaps.offense(), snaps.share()));
    }

    /**
     * Returns the player's targets and carries against his team's, empty
     * unless his team's weekly stats are filled and he played.
     */
    Optional<PlayerUsage.Volume> volume(DirectoryPlayer player) {
        if (!fills(FeedId.WEEKLY_STATS, player)) {
            return Optional.empty();
        }
        return stats.played(player.playerId()).flatMap(line -> stats.team(player.team())
                .map(team -> {
                    int targets = count(line, "rec_tgt");
                    int teamTargets = count(team, "rec_tgt");
                    int carries = count(line, "rush_att");
                    int teamCarries = count(team, "rush_att");
                    return new PlayerUsage.Volume(targets, teamTargets,
                            teamTargets > 0 ? (double) targets / teamTargets : null, carries,
                            teamCarries, teamCarries > 0 ? (double) carries / teamCarries : null);
                }));
    }

    private boolean fills(FeedId feed, DirectoryPlayer player) {
        return teamsByFeed.getOrDefault(feed, Set.of()).contains(player.team());
    }

    private static int count(Map<String, Double> line, String key) {
        return line.getOrDefault(key, 0.0).intValue();
    }
}
