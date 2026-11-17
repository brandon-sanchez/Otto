package otto.matchup;

import java.util.ArrayList;
import java.util.IntSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import otto.nflverse.NflverseStore;
import otto.nflverse.PfrPassing;
import otto.nflverse.PlayerUsage;
import otto.nflverse.Players;
import otto.nflverse.RushingAdvancedStats;
import otto.nflverse.WeeklyStats;

/**
 * Measures every player on one {@link PlayerStat} over a span of weeks,
 * each placed at the position he plays, so {@link LeagueRankings} can
 * rank one position or every position together from the same figures.
 */
final class PlayerMeasures {

    private static final Logger log = LoggerFactory.getLogger(PlayerMeasures.class);

    private final MatchupSources sources;
    private final NflverseStore store;

    PlayerMeasures(MatchupSources sources) {
        this.sources = sources;
        this.store = sources.store();
    }

    /** One player's measurement and the position he plays. */
    record Placed(Position position, Ranking.Measurement measurement) {
    }

    /**
     * Every player measured on one stat.
     *
     * @param unmatched the source's names the player register cannot place
     */
    record Measured(Ranking.Weeks weeks, List<Placed> players, List<String> unmatched) {

        Measured {
            players = List.copyOf(players);
            unmatched = List.copyOf(unmatched);
        }

        /** Returns the measurements of the players at one of the positions. */
        List<Ranking.Measurement> at(Set<Position> positions) {
            return players.stream()
                    .filter(placed -> positions.contains(placed.position()))
                    .map(Placed::measurement)
                    .toList();
        }
    }

    /** A value and how much it rests on; the value is empty when nothing measured it. */
    private record Figure(OptionalDouble value, int sample) {
    }

    /**
     * How a stat is read: from each player's games in {@link PlayerUsage},
     * from his weekly box scores, or from PFR's rushing lines. Each reads
     * only the rows that hold the stat, so a missing source is never a
     * quiet game.
     */
    private sealed interface Recipe {

        record FromGames(Predicate<PlayerUsage.Game> holds,
                Function<List<PlayerUsage.Game>, Figure> figure) implements Recipe {
        }

        record FromBoxScores(Function<List<WeeklyStats.StatLine>, Figure> figure)
                implements Recipe {
        }

        record FromRushing(Function<List<RushingAdvancedStats.RushLine>, Figure> figure)
                implements Recipe {
        }

        record PressuredRate() implements Recipe {
        }
    }

    /**
     * Returns every player's measurement over the span; empty when no
     * stored source holds the stat in it, or the stat is measured over
     * the season only and the span is narrower.
     */
    Optional<Measured> measure(PlayerStat stat, WeekSpan span) {
        if (!stat.spansWeeks() && !span.equals(WeekSpan.SEASON)) {
            return Optional.empty();
        }
        return switch (recipe(stat)) {
            case Recipe.FromGames games -> games(games, span);
            case Recipe.FromBoxScores boxScores -> boxScores(boxScores, span);
            case Recipe.FromRushing rushing -> rushing(rushing, span);
            case Recipe.PressuredRate _ -> pressuredRate();
        };
    }

    private static Recipe recipe(PlayerStat stat) {
        return switch (stat) {
            case SNAP_SHARE -> new Recipe.FromGames(game -> game.snaps() != null,
                    held -> new Figure(held.stream()
                            .mapToDouble(game -> game.snaps().share()).average(), held.size()));
            case TARGETS_PER_GAME -> perGame(PlayerUsage.Volume::targets);
            case CARRIES_PER_GAME -> perGame(PlayerUsage.Volume::carries);
            case TARGET_SHARE -> shareOfTeam(PlayerUsage.Volume::targets,
                    PlayerUsage.Volume::teamTargets);
            case CARRY_SHARE -> shareOfTeam(PlayerUsage.Volume::carries,
                    PlayerUsage.Volume::teamCarries);
            case AIR_YARDS -> opportunities(PlayerUsage.Opportunities::airYards);
            case RED_ZONE_TARGETS -> opportunities(PlayerUsage.Opportunities::redZoneTargets);
            case RED_ZONE_CARRIES -> opportunities(PlayerUsage.Opportunities::redZoneCarries);
            case END_ZONE_TARGETS -> opportunities(PlayerUsage.Opportunities::endZoneTargets);
            case FIRST_READ_TARGETS -> new Recipe.FromGames(
                    game -> game.opportunities() != null
                            && game.opportunities().firstReadTargets() != null,
                    held -> total(held, game -> game.opportunities().firstReadTargets()));
            case AIR_YARDS_SHARE -> mean(game -> game.receiving() == null ? null
                    : game.receiving().airYardsShare());
            case WOPR -> mean(game -> game.receiving() == null ? null : game.receiving().wopr());
            case EXPECTED_POINTS_PER_GAME -> mean(game -> game.expected() == null ? null
                    : game.expected().expectedFantasyPoints());
            case TARGETS -> boxTotal("rec_tgt");
            case CARRIES -> boxTotal("rush_att");
            case RECEPTIONS -> boxTotal("rec");
            case RECEIVING_YARDS -> boxTotal("rec_yd");
            case RUSHING_YARDS -> boxTotal("rush_yd");
            case PASSING_YARDS -> boxTotal("pass_yd");
            case RECEIVING_TOUCHDOWNS -> boxTotal("rec_td");
            case RUSHING_TOUCHDOWNS -> boxTotal("rush_td");
            case PASSING_TOUCHDOWNS -> boxTotal("pass_td");
            case YARDS_PER_CARRY -> boxRate("rush_yd", "rush_att");
            case YARDS_PER_TARGET -> boxRate("rec_yd", "rec_tgt");
            case YARDS_BEFORE_CONTACT_PER_CARRY ->
                    perCarry(RushingAdvancedStats.RushLine::yardsBeforeContact);
            case YARDS_AFTER_CONTACT_PER_CARRY ->
                    perCarry(RushingAdvancedStats.RushLine::yardsAfterContact);
            case YARDS_BEFORE_CONTACT ->
                    rushingTotal(RushingAdvancedStats.RushLine::yardsBeforeContact);
            case BROKEN_TACKLES -> rushingTotal(RushingAdvancedStats.RushLine::brokenTackles);
            case PRESSURED_RATE -> new Recipe.PressuredRate();
        };
    }

    private static Recipe perGame(ToIntFunction<PlayerUsage.Volume> own) {
        return new Recipe.FromGames(game -> game.volume() != null, held -> {
            int count = held.stream().mapToInt(game -> own.applyAsInt(game.volume())).sum();
            return new Figure(Ratio.of(count, held.size()), count);
        });
    }

    private static Recipe shareOfTeam(ToIntFunction<PlayerUsage.Volume> own,
            ToIntFunction<PlayerUsage.Volume> team) {
        return new Recipe.FromGames(game -> game.volume() != null, held -> {
            int count = held.stream().mapToInt(game -> own.applyAsInt(game.volume())).sum();
            int teams = held.stream().mapToInt(game -> team.applyAsInt(game.volume())).sum();
            return new Figure(Ratio.of(count, teams), count);
        });
    }

    private static Recipe opportunities(ToIntFunction<PlayerUsage.Opportunities> count) {
        return new Recipe.FromGames(game -> game.opportunities() != null,
                held -> total(held, game -> count.applyAsInt(game.opportunities())));
    }

    private static Figure total(List<PlayerUsage.Game> held,
            ToIntFunction<PlayerUsage.Game> count) {
        return new Figure(held.isEmpty() ? OptionalDouble.empty()
                : OptionalDouble.of(held.stream().mapToInt(count).sum()), held.size());
    }

    private static Recipe mean(Function<PlayerUsage.Game, Double> figure) {
        return new Recipe.FromGames(game -> figure.apply(game) != null,
                held -> new Figure(held.stream().map(figure).mapToDouble(Double::doubleValue)
                        .average(), held.size()));
    }

    private static Recipe boxTotal(String key) {
        return new Recipe.FromBoxScores(lines -> {
            List<Double> recorded = recorded(lines, key);
            return new Figure(recorded.isEmpty() ? OptionalDouble.empty()
                    : OptionalDouble.of(recorded.stream().mapToDouble(Double::doubleValue).sum()),
                    recorded.size());
        });
    }

    private static Recipe boxRate(String numeratorKey, String denominatorKey) {
        return new Recipe.FromBoxScores(lines -> {
            double numerator = recorded(lines, numeratorKey).stream()
                    .mapToDouble(Double::doubleValue).sum();
            int denominator = (int) Math.round(recorded(lines, denominatorKey).stream()
                    .mapToDouble(Double::doubleValue).sum());
            return new Figure(denominator == 0 ? OptionalDouble.empty()
                    : OptionalDouble.of(numerator / denominator), denominator);
        });
    }

    private static List<Double> recorded(List<WeeklyStats.StatLine> lines, String key) {
        return lines.stream()
                .map(line -> line.stats().get(key))
                .filter(Objects::nonNull)
                .toList();
    }

    private static Recipe perCarry(ToIntFunction<RushingAdvancedStats.RushLine> yards) {
        return new Recipe.FromRushing(lines -> {
            int carries = carries(lines);
            return new Figure(Ratio.of(lines.stream().mapToInt(yards).sum(), carries), carries);
        });
    }

    private static Recipe rushingTotal(ToIntFunction<RushingAdvancedStats.RushLine> count) {
        return new Recipe.FromRushing(lines -> new Figure(
                OptionalDouble.of(lines.stream().mapToInt(count).sum()), carries(lines)));
    }

    private static int carries(List<RushingAdvancedStats.RushLine> lines) {
        return lines.stream().mapToInt(RushingAdvancedStats.RushLine::carries).sum();
    }

    private Optional<Measured> games(Recipe.FromGames recipe, WeekSpan span) {
        return sources.usage().flatMap(usage -> {
            List<Placed> players = new ArrayList<>();
            IntSummaryStatistics weeks = new IntSummaryStatistics();
            for (String gsisId : usage.playerIds()) {
                List<PlayerUsage.Game> games = usage.games(gsisId);
                Optional<Position> position = Position.of(latest(games,
                        PlayerUsage.Game::position));
                if (position.isEmpty()) {
                    continue;
                }
                List<PlayerUsage.Game> held = games.stream()
                        .filter(game -> span.contains(game.week()))
                        .filter(recipe.holds())
                        .toList();
                held.forEach(game -> weeks.accept(game.week()));
                String name = Optional.ofNullable(latest(games, PlayerUsage.Game::player))
                        .or(() -> usage.profile(gsisId).map(Players.Player::name))
                        .orElse(null);
                players.add(new Placed(position.get(), measurement(gsisId, name,
                        recipe.figure().apply(held))));
            }
            return weeks(weeks, usage.season(), usage.priorSeasonFinal())
                    .map(covered -> new Measured(covered, players, List.of()));
        });
    }

    private Optional<Measured> boxScores(Recipe.FromBoxScores recipe, WeekSpan span) {
        return store.weeklyStats().flatMap(stats -> {
            Map<String, List<WeeklyStats.StatLine>> byPlayer = stats.rows().stream()
                    .filter(line -> span.contains(line.week()))
                    .filter(line -> Position.of(line.position()).isPresent())
                    .collect(Collectors.groupingBy(WeeklyStats.StatLine::gsisId,
                            LinkedHashMap::new, Collectors.toList()));
            IntSummaryStatistics weeks = new IntSummaryStatistics();
            List<Placed> players = new ArrayList<>();
            byPlayer.forEach((gsisId, lines) -> {
                lines.forEach(line -> weeks.accept(line.week()));
                WeeklyStats.StatLine last = lines.getLast();
                players.add(new Placed(Position.of(last.position()).orElseThrow(),
                        measurement(gsisId, last.player(), recipe.figure().apply(lines))));
            });
            return weeks(weeks, stats.season(), stats.priorSeasonFinal())
                    .map(covered -> new Measured(covered, players, List.of()));
        });
    }

    private Optional<Measured> rushing(Recipe.FromRushing recipe, WeekSpan span) {
        Optional<RushingAdvancedStats> stats = store.rushingAdvancedStats();
        if (stats.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Registered> register = register();
        List<RushingAdvancedStats.RushLine> inSpan = stats.get().rows().stream()
                .filter(line -> span.contains(line.week()))
                .toList();
        List<String> unmatched = unmatched(inSpan.stream()
                .map(line -> new PfrEntry(line.pfrId(), line.player())));
        Map<String, List<RushingAdvancedStats.RushLine>> byRunner = inSpan.stream()
                .filter(line -> register.containsKey(line.pfrId()))
                .collect(Collectors.groupingBy(RushingAdvancedStats.RushLine::pfrId,
                        LinkedHashMap::new, Collectors.toList()));
        IntSummaryStatistics weeks = new IntSummaryStatistics();
        List<Placed> players = new ArrayList<>();
        byRunner.forEach((pfrId, lines) -> {
            lines.forEach(line -> weeks.accept(line.week()));
            Registered player = register.get(pfrId);
            players.add(new Placed(player.position(), measurement(player.player().gsisId(),
                    player.player().name(), recipe.figure().apply(lines))));
        });
        return weeks(weeks, stats.get().season(), stats.get().priorSeasonFinal())
                .map(covered -> new Measured(covered, players, unmatched));
    }

    private Optional<Measured> pressuredRate() {
        Optional<PfrPassing> passing = store.pfrPassing();
        Optional<PressureRates> rates = sources.pressureRates();
        if (passing.isEmpty() || rates.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Registered> register = register();
        List<String> unmatched = unmatched(rates.get().passers().stream()
                .map(passer -> new PfrEntry(passer.pfrId(), passer.player())));
        List<Placed> players = rates.get().passers().stream()
                .filter(passer -> register.containsKey(passer.pfrId()))
                .map(passer -> {
                    Registered player = register.get(passer.pfrId());
                    return new Placed(player.position(), new Ranking.Measurement(
                            player.player().gsisId(), player.player().name(), passer.rate(),
                            passer.dropbacks()));
                })
                .toList();
        IntSummaryStatistics weeks = passing.get().rows().stream()
                .filter(line -> register.containsKey(line.pfrId()))
                .mapToInt(PfrPassing.PasserLine::week)
                .summaryStatistics();
        return weeks(weeks, rates.get().season(), rates.get().priorSeasonFinal())
                .map(covered -> new Measured(covered, players, unmatched));
    }

    private static Ranking.Measurement measurement(String gsisId, String name, Figure figure) {
        return new Ranking.Measurement(gsisId, name, figure.value(), figure.sample());
    }

    /**
     * Returns the names of the PFR entries the player register does not
     * list at any position, and logs them, so a player missing from the
     * register is reported rather than silently left out of a ranking.
     */
    private List<String> unmatched(Stream<PfrEntry> entries) {
        Set<String> listed = store.players()
                .map(players -> players.byPfrId().keySet())
                .orElse(Set.of());
        List<String> names = entries
                .filter(entry -> !listed.contains(entry.pfrId()))
                .map(PfrEntry::name)
                .distinct()
                .sorted()
                .toList();
        if (!names.isEmpty()) {
            log.warn("league_rankings_unmatched_pfr_entries count={} names={}", names.size(),
                    names);
        }
        return names;
    }

    /** One row of a PFR file, by its PFR id and the name PFR writes. */
    private record PfrEntry(String pfrId, String name) {
    }

    /** A register player at a position Otto ranks. */
    private record Registered(Players.Player player, Position position) {
    }

    /** Returns the register's players at a ranked position, by PFR id. */
    private Map<String, Registered> register() {
        Map<String, Registered> byPfr = new LinkedHashMap<>();
        store.players().ifPresent(players -> players.byPfrId().forEach((pfrId, player) ->
                Position.of(player.position()).ifPresent(position ->
                        byPfr.put(pfrId, new Registered(player, position)))));
        return byPfr;
    }

    private static Optional<Ranking.Weeks> weeks(IntSummaryStatistics weeks, String season,
            boolean priorSeasonFinal) {
        return weeks.getCount() == 0
                ? Optional.empty()
                : Optional.of(new Ranking.Weeks(season, priorSeasonFinal, weeks.getMin(),
                        weeks.getMax()));
    }

    private static <T> String latest(List<T> oldestFirst, Function<T, String> field) {
        String latest = null;
        for (T each : oldestFirst) {
            String value = field.apply(each);
            if (value != null) {
                latest = value;
            }
        }
        return latest;
    }
}
