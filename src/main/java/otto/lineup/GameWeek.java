package otto.lineup;

import java.time.Instant;
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

    private static final String COMPLETE = "complete";

    public static GameWeek of(List<SleeperAdapter.Game> games) {
        Map<String, SleeperAdapter.Game> byTeam = new HashMap<>();
        for (SleeperAdapter.Game game : games) {
            byTeam.put(game.homeTeam(), game);
            byTeam.put(game.awayTeam(), game);
        }
        return new GameWeek(Map.copyOf(byTeam));
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
                && !COMPLETE.equals(game.status());
    }
}
