package otto.board;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import otto.directory.DirectoryPlayer;
import otto.nflverse.PlayerUsage;

/**
 * Sleeper's stat lines for the latest completed week, standing in for
 * the teams whose nflverse weekly stats or snap counts had not arrived
 * when the board was built. nflverse stays the source for every team it
 * holds: a team is filled only when its nflverse week is short and
 * Sleeper holds that team's game.
 *
 * <p>Sleeper leaves a stat out of a line when it is zero, so a player
 * whose line is present had no target or carry Sleeper did not list.
 * Only Sleeper's snaps, targets, carries and box score are read; every
 * other part of a game, such as air-yards share, red-zone and end-zone
 * work or expected points, stays unknown.
 *
 * <p>A player is matched to a team by his Player Directory team, since
 * Sleeper's lines carry none.
 *
 * @param week the week filled; 0 when nothing is
 * @param statsTeams the teams whose weekly stats come from Sleeper
 * @param snapTeams the teams whose snap counts come from Sleeper
 * @param opponents each filled team's opponent that week
 * @param lines Sleeper's lines by Sleeper player id, plus each team's
 *        offense under {@code TEAM_} and its code
 */
record SleeperFill(int week, Set<String> statsTeams, Set<String> snapTeams,
        Map<String, String> opponents, Map<String, Map<String, Double>> lines) {

    /** The evidence source every value from Sleeper is labelled with. */
    static final String SOURCE = "sleeper:stats";

    /** The fill of a week nflverse holds in full. */
    static final SleeperFill NONE = new SleeperFill(0, Set.of(), Set.of(), Map.of(), Map.of());

    private static final String TEAM_PREFIX = "TEAM_";

    /**
     * The stats a played line is priced and described from: the keys the
     * nflverse weekly stats are translated to, so both sources price a
     * week alike. Sleeper's own bonus counters are left out, because
     * {@link otto.lineup.LeagueScoring} works out the tight-end premium
     * itself.
     */
    private static final List<String> SCORED_STATS = List.of("pass_yd", "pass_td",
            "pass_int", "pass_2pt", "rush_att", "rush_yd", "rush_td", "rush_2pt", "rec_tgt",
            "rec", "rec_yd", "rec_td", "rec_2pt", "fum_lost");

    SleeperFill {
        statsTeams = Set.copyOf(statsTeams);
        snapTeams = Set.copyOf(snapTeams);
        opponents = Map.copyOf(opponents);
        lines = Map.copyOf(lines);
    }

    /** Returns whether Sleeper's lines hold the team's game. */
    static boolean holds(Map<String, Map<String, Double>> lines, String team) {
        return lines.containsKey(TEAM_PREFIX + team);
    }

    /**
     * Returns the player's scored stats for the filled week, empty
     * unless his team's weekly stats are filled and Sleeper has a line
     * for him.
     */
    Optional<Map<String, Double>> stats(DirectoryPlayer player) {
        if (!statsTeams.contains(player.team())) {
            return Optional.empty();
        }
        return line(player).map(line -> {
            Map<String, Double> scored = new LinkedHashMap<>();
            SCORED_STATS.forEach(key -> {
                Double value = line.get(key);
                if (value != null) {
                    scored.put(key, value);
                }
            });
            return scored;
        });
    }

    /** Returns the team the player's filled game was against, or null when unknown. */
    String opponent(DirectoryPlayer player) {
        return opponents.get(player.team());
    }

    /**
     * Returns the player's offensive snaps, empty unless his team's
     * snap counts are filled and his line counts his and his team's.
     */
    Optional<PlayerUsage.Snaps> snaps(DirectoryPlayer player) {
        if (!snapTeams.contains(player.team())) {
            return Optional.empty();
        }
        return line(player).flatMap(line -> {
            Double played = line.get("off_snp");
            Double team = line.get("tm_off_snp");
            if (played == null || team == null || team <= 0) {
                return Optional.empty();
            }
            return Optional.of(new PlayerUsage.Snaps(played.intValue(), played / team));
        });
    }

    /**
     * Returns the player's targets and carries against his team's, empty
     * unless his team's weekly stats are filled and Sleeper has both his
     * line and his team's.
     */
    Optional<PlayerUsage.Volume> volume(DirectoryPlayer player) {
        Map<String, Double> team = lines.get(TEAM_PREFIX + player.team());
        if (!statsTeams.contains(player.team()) || team == null) {
            return Optional.empty();
        }
        return line(player).map(line -> {
            int targets = count(line, "rec_tgt");
            int teamTargets = count(team, "rec_tgt");
            int carries = count(line, "rush_att");
            int teamCarries = count(team, "rush_att");
            return new PlayerUsage.Volume(targets, teamTargets,
                    teamTargets > 0 ? (double) targets / teamTargets : null, carries,
                    teamCarries, teamCarries > 0 ? (double) carries / teamCarries : null);
        });
    }

    private Optional<Map<String, Double>> line(DirectoryPlayer player) {
        return Optional.ofNullable(lines.get(player.playerId()));
    }

    private static int count(Map<String, Double> line, String key) {
        return line.getOrDefault(key, 0.0).intValue();
    }
}
