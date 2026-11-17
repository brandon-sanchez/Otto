package otto.nflverse;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * One player's usage summed over a set of his games. Each part counts
 * only the games whose source holds it, and names the weeks it could not
 * count, so a source that is missing a game is never read as a quiet
 * game. A stats answer and the league's stat ranks both read a player's
 * usage from here.
 *
 * @param snaps his offensive snaps, and the mean of his per-game snap
 *        shares, the way the league ranking averages them
 * @param volume his targets and carries against his team's
 * @param looks his red-zone, end-zone and air-yards work from the play-by-play
 * @param firstReads his first-read targets over the games FTN charted
 * @param expected his full-PPR points against what his opportunities were
 *        worth, over the games that carry both
 * @param airYardsShare the mean of nflverse's per-game air-yards share
 * @param wopr the mean of nflverse's per-game WOPR
 * @param expectedPerGame the mean of his per-game expected full-PPR points
 */
public record UsageTotals(Part<Snaps> snaps, Part<Volume> volume, Part<Looks> looks,
        Part<Integer> firstReads, Part<Expected> expected, Mean airYardsShare, Mean wopr,
        Mean expectedPerGame) {

    /**
     * A sum over the games whose source holds it.
     *
     * @param value null when no game's source holds it
     * @param counted the weeks summed
     * @param missing the weeks he played that the source does not hold
     */
    public record Part<T>(T value, List<Integer> counted, List<Integer> missing) {

        public Part {
            counted = List.copyOf(counted);
            missing = List.copyOf(missing);
        }
    }

    /**
     * A per-game mean over the games that hold a figure.
     *
     * @param value empty when no game holds it
     * @param games how many games it averages
     */
    public record Mean(OptionalDouble value, int games) {
    }

    /** @param meanShare from 0 to 1 */
    public record Snaps(int snaps, double meanShare) {
    }

    public record Volume(int targets, int teamTargets, int carries, int teamCarries) {
    }

    public record Looks(int redZoneTargets, int redZoneCarries, int endZoneTargets,
            int airYards) {
    }

    public record Expected(double points, double expectedPoints) {
    }

    /** Sums the games given, in the order given. */
    public static UsageTotals of(List<PlayerUsage.Game> games) {
        return new UsageTotals(
                part(games, PlayerUsage.Game::snaps, held -> new Snaps(
                        sum(held, PlayerUsage.Snaps::offenseSnaps),
                        held.stream().mapToDouble(PlayerUsage.Snaps::share).average()
                                .orElseThrow())),
                part(games, PlayerUsage.Game::volume, held -> new Volume(
                        sum(held, PlayerUsage.Volume::targets),
                        sum(held, PlayerUsage.Volume::teamTargets),
                        sum(held, PlayerUsage.Volume::carries),
                        sum(held, PlayerUsage.Volume::teamCarries))),
                part(games, PlayerUsage.Game::opportunities, held -> new Looks(
                        sum(held, PlayerUsage.Opportunities::redZoneTargets),
                        sum(held, PlayerUsage.Opportunities::redZoneCarries),
                        sum(held, PlayerUsage.Opportunities::endZoneTargets),
                        sum(held, PlayerUsage.Opportunities::airYards))),
                part(games, game -> game.opportunities() == null ? null
                        : game.opportunities().firstReadTargets(),
                        held -> held.stream().mapToInt(Integer::intValue).sum()),
                part(games, game -> game.expected() == null
                                || game.expected().fantasyPoints() == null
                                || game.expected().expectedFantasyPoints() == null
                                ? null : game.expected(),
                        held -> new Expected(
                                held.stream().mapToDouble(PlayerUsage.Expected::fantasyPoints)
                                        .sum(),
                                held.stream()
                                        .mapToDouble(PlayerUsage.Expected::expectedFantasyPoints)
                                        .sum())),
                mean(games, game -> game.receiving() == null ? null
                        : game.receiving().airYardsShare()),
                mean(games, game -> game.receiving() == null ? null
                        : game.receiving().wopr()),
                mean(games, game -> game.expected() == null ? null
                        : game.expected().expectedFantasyPoints()));
    }

    private static <S, T> Part<T> part(List<PlayerUsage.Game> games,
            Function<PlayerUsage.Game, S> source, Function<List<S>, T> total) {
        List<PlayerUsage.Game> held = games.stream()
                .filter(game -> source.apply(game) != null)
                .toList();
        List<Integer> missing = games.stream()
                .filter(game -> source.apply(game) == null)
                .map(PlayerUsage.Game::week)
                .toList();
        return new Part<>(held.isEmpty() ? null
                : total.apply(held.stream().map(source).toList()),
                held.stream().map(PlayerUsage.Game::week).toList(), missing);
    }

    private static Mean mean(List<PlayerUsage.Game> games,
            Function<PlayerUsage.Game, Double> figure) {
        List<Double> held = games.stream().map(figure).filter(Objects::nonNull).toList();
        return new Mean(held.stream().mapToDouble(Double::doubleValue).average(), held.size());
    }

    private static <S> int sum(List<S> held, ToIntFunction<S> count) {
        return held.stream().mapToInt(count).sum();
    }
}
