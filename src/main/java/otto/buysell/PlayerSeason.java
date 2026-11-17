package otto.buysell;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * One skill player's games of one season from ffopportunity: what he
 * scored, what his opportunities were worth, and his share of his
 * offence's targets and carries. A game counts only when the file gives
 * both his points and his expected points; ffopportunity lists a player
 * only in a game where he had an opportunity.
 *
 * @param games oldest first
 */
public record PlayerSeason(String gsisId, String position, List<Game> games) {

    public PlayerSeason {
        games = List.copyOf(games);
    }

    /**
     * One game, in ffopportunity's full-PPR scoring.
     *
     * @param targets null when the file left it blank, as are the other
     *        attempt counts
     */
    public record Game(int week, String team, double points, double expectedPoints,
            double touchdowns, double expectedTouchdowns, Double targets, Double teamTargets,
            Double carries, Double teamCarries) {
    }

    public int gamesPlayed() {
        return games.size();
    }

    public double totalPoints() {
        return sum(Game::points);
    }

    public double pointsPerGame() {
        return perGame(Game::points);
    }

    /** His xFP per game: what an average player would have scored with his opportunities. */
    public double expectedPerGame() {
        return perGame(Game::expectedPoints);
    }

    /** His FPOE per game: points per game above his xFP, negative below it. */
    public double overExpectedPerGame() {
        return pointsPerGame() - expectedPerGame();
    }

    public double touchdowns() {
        return sum(Game::touchdowns);
    }

    public double expectedTouchdowns() {
        return sum(Game::expectedTouchdowns);
    }

    /** Returns his share of the targets his offences threw in his games, from 0 to 1. */
    public Optional<Double> targetShare() {
        return share(Game::targets, Game::teamTargets);
    }

    /** Returns his share of the carries his offences ran in his games, from 0 to 1. */
    public Optional<Double> carryShare() {
        return share(Game::carries, Game::teamCarries);
    }

    /** Returns his newest {@code count} games, or all of them when he has fewer. */
    public PlayerSeason last(int count) {
        return new PlayerSeason(gsisId, position,
                games.subList(Math.max(0, games.size() - count), games.size()));
    }

    /** Returns the team of his newest game, empty before his first. */
    public Optional<String> latestTeam() {
        return games.isEmpty() ? Optional.empty() : Optional.of(games.getLast().team());
    }

    private double perGame(ToDoubleFunction<Game> value) {
        return games.isEmpty() ? 0 : sum(value) / games.size();
    }

    private double sum(ToDoubleFunction<Game> value) {
        return games.stream().mapToDouble(value).sum();
    }

    private Optional<Double> share(Function<Game, Double> mine, Function<Game, Double> team) {
        double own = 0;
        double all = 0;
        for (Game game : games) {
            Double played = mine.apply(game);
            Double total = team.apply(game);
            if (played != null && total != null) {
                own += played;
                all += total;
            }
        }
        return all > 0 ? Optional.of(own / all) : Optional.empty();
    }
}
