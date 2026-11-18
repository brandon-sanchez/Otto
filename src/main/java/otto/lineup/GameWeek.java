package otto.lineup;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import otto.sleeper.SleeperAdapter;

/**
 * One week's games indexed by team. A team with no game is on bye.
 * A player locks at their game's kickoff.
 */
public record GameWeek(Map<String, SleeperAdapter.Game> byTeam) {

    /**
     * The last week of the NFL regular season. Weeks after it are the NFL
     * playoffs, which no fantasy league plays, projects or scores.
     */
    public static final int LAST_NFL_WEEK = 18;

    private static final String COMPLETE = "complete";

    public static GameWeek of(List<SleeperAdapter.Game> games) {
        Map<String, SleeperAdapter.Game> byTeam = new HashMap<>();
        for (SleeperAdapter.Game game : games) {
            byTeam.put(game.homeTeam(), game);
            byTeam.put(game.awayTeam(), game);
        }
        return new GameWeek(Map.copyOf(byTeam));
    }

    /** Every game of the week once, in kickoff order. */
    public List<SleeperAdapter.Game> schedule() {
        return byTeam.values().stream()
                .distinct()
                .sorted(Comparator.comparing(SleeperAdapter.Game::startTime)
                        .thenComparing(SleeperAdapter.Game::homeTeam))
                .toList();
    }

    /** True once Sleeper marks the game final. */
    public static boolean complete(SleeperAdapter.Game game) {
        return COMPLETE.equals(game.status());
    }

    public Optional<Instant> lockFor(String team) {
        return Optional.ofNullable(team)
                .map(byTeam::get)
                .map(SleeperAdapter.Game::startTime);
    }

    public boolean onBye(String team) {
        return team != null && !team.isBlank() && !byTeam.containsKey(team);
    }

    /** Who this team plays this week; empty on a bye. */
    public Optional<String> opponentOf(String team) {
        return Optional.ofNullable(team)
                .map(byTeam::get)
                .map(game -> team.equals(game.homeTeam()) ? game.awayTeam() : game.homeTeam());
    }

    public boolean locked(String team, Instant now) {
        return lockFor(team).filter(lock -> !now.isBefore(lock)).isPresent();
    }

    /** True from kickoff until the game completes. */
    public boolean underway(String team, Instant now) {
        SleeperAdapter.Game game = team == null ? null : byTeam.get(team);
        return game != null
                && !now.isBefore(game.startTime())
                && !complete(game);
    }
}
