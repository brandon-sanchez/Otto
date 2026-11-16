package otto.ask;

import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import otto.nflverse.PlayerUsage;

/**
 * One player's usage summed over the games of a stats answer. Each part
 * counts only the games whose source holds it, and names the weeks it
 * could not count, so a source that is missing a game is never read as
 * a quiet game.
 *
 * @param snaps his offensive snaps, and the mean of his per-game snap
 *        shares, the way the league ranking averages them
 * @param volume his targets and carries against his team's
 * @param airYardsShare the mean of nflverse's per-game air-yards share,
 *        empty when no game has one
 * @param wopr the mean of nflverse's per-game WOPR, empty when no game has one
 * @param looks his red-zone, end-zone and air-yards work from the play-by-play
 * @param firstReads his first-read targets over the games FTN charted
 * @param expected his full-PPR points against what his opportunities were worth
 */
record StatTotals(Part<Snaps> snaps, Part<Volume> volume, OptionalDouble airYardsShare,
        OptionalDouble wopr, Part<Looks> looks, Part<Integer> firstReads,
        Part<Expected> expected) {

    /**
     * A sum over the games whose source holds it.
     *
     * @param value null when no game's source holds it
     * @param counted the weeks summed
     * @param missing the weeks he played that the source does not hold
     */
    record Part<T>(T value, List<Integer> counted, List<Integer> missing) {

        Part {
            counted = List.copyOf(counted);
            missing = List.copyOf(missing);
        }
    }

    /** @param meanShare from 0 to 1 */
    record Snaps(int snaps, double meanShare) {
    }

    record Volume(int targets, int teamTargets, int carries, int teamCarries) {
    }

    record Looks(int redZoneTargets, int redZoneCarries, int endZoneTargets, int airYards) {
    }

    record Expected(double points, double expectedPoints) {
    }

    /** Sums the games he played in the span, oldest first. */
    static StatTotals of(List<PlayerUsage.Game> games) {
        return new StatTotals(
                part(games, PlayerUsage.Game::snaps, held -> new Snaps(
                        sum(held, PlayerUsage.Snaps::offenseSnaps),
                        held.stream().mapToDouble(PlayerUsage.Snaps::share).average()
                                .orElseThrow())),
                part(games, PlayerUsage.Game::volume, held -> new Volume(
                        sum(held, PlayerUsage.Volume::targets),
                        sum(held, PlayerUsage.Volume::teamTargets),
                        sum(held, PlayerUsage.Volume::carries),
                        sum(held, PlayerUsage.Volume::teamCarries))),
                mean(games, receiving -> receiving.airYardsShare()),
                mean(games, receiving -> receiving.wopr()),
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
                                        .sum())));
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

    private static OptionalDouble mean(List<PlayerUsage.Game> games,
            Function<PlayerUsage.Receiving, Double> figure) {
        return games.stream()
                .map(PlayerUsage.Game::receiving)
                .filter(Objects::nonNull)
                .map(figure)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average();
    }

    private static <S> int sum(List<S> held, ToIntFunction<S> count) {
        return held.stream().mapToInt(count).sum();
    }
}
