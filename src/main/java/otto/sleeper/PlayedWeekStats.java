package otto.sleeper;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Sleeper's stat lines for one played week, read from its undocumented
 * stats endpoint and split into players and team offenses.
 *
 * <p>Sleeper leaves a stat out of a line when it is zero, and a
 * player's line names no team. Each team's offense comes under a
 * {@code TEAM_} key, and the key is there only once the team's game has
 * started.
 *
 * @param players each player's line, by Sleeper player id
 * @param teams each team offense's line, by team code
 */
public record PlayedWeekStats(Map<String, Map<String, Double>> players,
        Map<String, Map<String, Double>> teams) {

    /** A week with no lines. */
    public static final PlayedWeekStats EMPTY = new PlayedWeekStats(Map.of(), Map.of());

    private static final String TEAM_PREFIX = "TEAM_";
    private static final String OFFENSE_SNAPS = "off_snp";
    private static final String TEAM_OFFENSE_SNAPS = "tm_off_snp";
    private static final String GAMES_PLAYED = "gp";

    public PlayedWeekStats {
        players = Map.copyOf(players);
        teams = Map.copyOf(teams);
    }

    /**
     * One player's offensive snaps in his game.
     *
     * @param offense the offensive snaps he played
     * @param teamOffense his team's offensive snaps, above 0
     */
    public record Snaps(int offense, int teamOffense) {

        /** Returns his share of his team's offensive snaps, from 0 to 1. */
        public double share() {
            return (double) offense / teamOffense;
        }
    }

    /**
     * Splits the endpoint's lines. Keys that are neither a player id nor
     * a {@code TEAM_} key, such as the team defenses, are left out.
     */
    static PlayedWeekStats of(Map<String, Map<String, Double>> lines) {
        Map<String, Map<String, Double>> players = new HashMap<>();
        Map<String, Map<String, Double>> teams = new HashMap<>();
        lines.forEach((key, line) -> {
            if (key.startsWith(TEAM_PREFIX)) {
                teams.put(key.substring(TEAM_PREFIX.length()), line);
            } else if (key.chars().allMatch(Character::isDigit)) {
                players.put(key, line);
            }
        });
        return new PlayedWeekStats(players, teams);
    }

    /** Returns whether Sleeper holds the team's game. */
    public boolean holdsGame(String team) {
        return teams.containsKey(team);
    }

    /** Returns whether any player's line counts his team's snaps. */
    public boolean countsSnaps() {
        return players.values().stream().anyMatch(line -> line.containsKey(TEAM_OFFENSE_SNAPS));
    }

    /**
     * Returns the player's line, empty unless he played: a line with a
     * game or an offensive snap on it.
     */
    public Optional<Map<String, Double>> played(String playerId) {
        return Optional.ofNullable(players.get(playerId))
                .filter(line -> line.getOrDefault(GAMES_PLAYED, 0.0) > 0
                        || line.getOrDefault(OFFENSE_SNAPS, 0.0) > 0);
    }

    /** Returns the team offense's line, empty when Sleeper does not hold its game. */
    public Optional<Map<String, Double>> team(String team) {
        return Optional.ofNullable(teams.get(team));
    }

    /** Returns the player's snaps, empty unless his line counts his and his team's. */
    public Optional<Snaps> snaps(String playerId) {
        return Optional.ofNullable(players.get(playerId)).flatMap(line -> {
            Double played = line.get(OFFENSE_SNAPS);
            Double team = line.get(TEAM_OFFENSE_SNAPS);
            if (played == null || team == null || team <= 0) {
                return Optional.empty();
            }
            return Optional.of(new Snaps(played.intValue(), team.intValue()));
        });
    }
}
