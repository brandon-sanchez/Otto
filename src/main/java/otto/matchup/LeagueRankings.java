package otto.matchup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IntSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import otto.nflverse.DefenseVersusPosition;
import otto.nflverse.NflverseStore;
import otto.nflverse.PfrDefense;
import otto.nflverse.PfrPassing;
import otto.nflverse.PlayerUsage;
import otto.nflverse.Players;
import otto.nflverse.RushingAdvancedStats;

/**
 * Ranks players within a position, defenses against a position, and
 * remaining schedules for a position, for the stats in {@link PlayerStat},
 * {@link DefenseStat} and {@link ScheduleStat}, so that Otto can say "3rd
 * in tight-end volume" or "the 1st pass rush in the league".
 *
 * <p>Rankings read from one {@link MatchupSources}, and each stat is
 * ranked at most once per position, then kept. Rank 1 is the most of a
 * player stat, the toughest defense and the hardest remaining schedule;
 * {@link Ranking} says why and how ties fall. Players are
 * identified by gsis id. A player read from a PFR file is placed at a
 * position through the nflverse player register, and one the register
 * does not list is left out of every population, named in
 * {@link Ranking#unmatched()} and logged.
 */
public final class LeagueRankings {

    private static final Logger log = LoggerFactory.getLogger(LeagueRankings.class);

    private final MatchupSources sources;
    private final NflverseStore store;
    private final Map<RankingKey, Optional<Ranking>> ranked = new HashMap<>();

    LeagueRankings(MatchupSources sources) {
        this.sources = sources;
        this.store = sources.store();
    }

    private record RankingKey(RankedStat stat, Position position) {
    }

    /**
     * Ranks every player at the position on the stat, season to date.
     *
     * @param position the position the players play
     * @return empty when no stored source holds the stat for any week
     */
    public Optional<Ranking> players(PlayerStat stat, Position position) {
        return ranked.computeIfAbsent(new RankingKey(stat, position), key -> switch (stat) {
            case TARGETS_PER_GAME, TARGET_SHARE, CARRIES_PER_GAME, CARRY_SHARE, SNAP_SHARE ->
                    usage(stat, position);
            case YARDS_BEFORE_CONTACT_PER_CARRY -> yardsBeforeContact(position);
            case PRESSURED_RATE -> pressuredRate(position);
        });
    }

    /**
     * Ranks every defense on the stat against the position, season to date.
     *
     * @param position one of {@link DefenseStat#positions()}
     * @return empty when no stored source holds the stat, or when the
     *         defense-versus-position table does not say which weeks it covers
     * @throws IllegalArgumentException when the stat says nothing about the position
     */
    public Optional<Ranking> defenses(DefenseStat stat, Position position) {
        if (!stat.positions().contains(position)) {
            throw new IllegalArgumentException("%s is not ranked against %s, only %s"
                    .formatted(stat.label(), position, stat.positions()));
        }
        return ranked.computeIfAbsent(new RankingKey(stat, position), key -> switch (stat) {
            case POINTS_ALLOWED -> fromTable(stat, position,
                    defense -> defense.byPosition().get(position.name()));
            case RUSHING_YARDS_ALLOWED -> fromTable(stat, position,
                    DefenseVersusPosition.TeamDefense::rushing);
            case YARDS_BEFORE_CONTACT_ALLOWED -> contactAllowed(position);
            case PRESSURE_RATE -> pressureRate(position);
            case BLITZ_RATE -> blitzRate(position);
        });
    }

    /**
     * Ranks every team's remaining regular-season schedule for the position.
     *
     * @return empty when no schedule or defense table is stored, or no
     *         team has a remaining game against a rated defense
     */
    public Optional<Ranking> schedules(ScheduleStat stat, Position position) {
        return ranked.computeIfAbsent(new RankingKey(stat, position), key -> sources
                .scheduleStrength()
                .filter(strength -> !strength.byTeam().isEmpty())
                .map(strength -> {
                    List<Ranking.Measurement> measurements = new ArrayList<>();
                    strength.byTeam().forEach((team, ratings) -> {
                        ScheduleStrength.Rating rating = ratings.get(position.name());
                        measurements.add(new Ranking.Measurement(team, team,
                                rating == null ? null : rating.perGame(),
                                rating == null ? 0 : rating.games()));
                    });
                    return Ranking.rank(stat, position, new Ranking.Weeks(strength.season(),
                            false, strength.firstWeek(), strength.lastWeek()), measurements);
                }));
    }

    private Optional<Ranking> usage(PlayerStat stat, Position position) {
        UsageShape shape = shape(stat);
        return sources.usage().flatMap(usage -> {
            List<Ranking.Measurement> measurements = new ArrayList<>();
            IntSummaryStatistics weeks = new IntSummaryStatistics();
            for (String gsisId : usage.playerIds()) {
                List<PlayerUsage.Game> games = usage.games(gsisId);
                if (!position.name().equals(latest(games, PlayerUsage.Game::position))) {
                    continue;
                }
                List<PlayerUsage.Game> held = games.stream().filter(holds(shape)).toList();
                held.forEach(game -> weeks.accept(game.week()));
                measurements.add(usageMeasurement(shape, gsisId,
                        latest(games, PlayerUsage.Game::player), held));
            }
            return span(weeks, usage.season(), usage.priorSeasonFinal())
                    .map(span -> Ranking.rank(stat, position, span, measurements));
        });
    }

    /**
     * How a usage stat is measured from a player's games: the mean of his
     * per-game snap share, his own count per game, or his own count over
     * his team's.
     */
    private sealed interface UsageShape {

        record MeanSnapShare() implements UsageShape {
        }

        record PerGame(ToIntFunction<PlayerUsage.Volume> own) implements UsageShape {
        }

        record ShareOfTeam(ToIntFunction<PlayerUsage.Volume> own,
                ToIntFunction<PlayerUsage.Volume> team) implements UsageShape {
        }
    }

    private static UsageShape shape(PlayerStat stat) {
        return switch (stat) {
            case SNAP_SHARE -> new UsageShape.MeanSnapShare();
            case TARGETS_PER_GAME -> new UsageShape.PerGame(PlayerUsage.Volume::targets);
            case CARRIES_PER_GAME -> new UsageShape.PerGame(PlayerUsage.Volume::carries);
            case TARGET_SHARE -> new UsageShape.ShareOfTeam(PlayerUsage.Volume::targets,
                    PlayerUsage.Volume::teamTargets);
            case CARRY_SHARE -> new UsageShape.ShareOfTeam(PlayerUsage.Volume::carries,
                    PlayerUsage.Volume::teamCarries);
            case YARDS_BEFORE_CONTACT_PER_CARRY, PRESSURED_RATE ->
                    throw new IllegalArgumentException(stat + " is not a usage stat");
        };
    }

    private static Predicate<PlayerUsage.Game> holds(UsageShape shape) {
        return shape instanceof UsageShape.MeanSnapShare
                ? game -> game.snaps() != null
                : game -> game.volume() != null;
    }

    private static Ranking.Measurement usageMeasurement(UsageShape shape, String gsisId,
            String name, List<PlayerUsage.Game> held) {
        return switch (shape) {
            case UsageShape.MeanSnapShare _ -> new Ranking.Measurement(gsisId, name,
                    held.stream().mapToDouble(game -> game.snaps().share()).average(),
                    held.size());
            case UsageShape.PerGame perGame -> {
                int own = sum(held, perGame.own());
                yield new Ranking.Measurement(gsisId, name, Ratio.of(own, held.size()), own);
            }
            case UsageShape.ShareOfTeam share -> {
                int own = sum(held, share.own());
                yield new Ranking.Measurement(gsisId, name, Ratio.of(own, sum(held, share.team())),
                        own);
            }
        };
    }

    private static int sum(List<PlayerUsage.Game> held, ToIntFunction<PlayerUsage.Volume> count) {
        return held.stream().mapToInt(game -> count.applyAsInt(game.volume())).sum();
    }

    private Optional<Ranking> yardsBeforeContact(Position position) {
        Optional<RushingAdvancedStats> stats = store.rushingAdvancedStats();
        if (stats.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Players.Player> register = registerAt(position);
        List<String> unmatched = unmatched(stats.get().rows().stream()
                .map(line -> new PfrEntry(line.pfrId(), line.player())));
        Map<String, List<RushingAdvancedStats.RushLine>> byRunner = stats.get().rows().stream()
                .filter(line -> register.containsKey(line.pfrId()))
                .collect(Collectors.groupingBy(RushingAdvancedStats.RushLine::pfrId));
        IntSummaryStatistics weeks = new IntSummaryStatistics();
        List<Ranking.Measurement> measurements = new ArrayList<>();
        byRunner.forEach((pfrId, lines) -> {
            lines.forEach(line -> weeks.accept(line.week()));
            int carries = carries(lines);
            Players.Player player = register.get(pfrId);
            measurements.add(new Ranking.Measurement(player.gsisId(), player.name(),
                    Ratio.of(yardsBeforeContact(lines), carries), carries));
        });
        return span(weeks, stats.get().season(), stats.get().priorSeasonFinal())
                .map(span -> Ranking.rank(PlayerStat.YARDS_BEFORE_CONTACT_PER_CARRY, position,
                        span, measurements).withUnmatched(unmatched));
    }

    private Optional<Ranking> pressuredRate(Position position) {
        Optional<PfrPassing> passing = store.pfrPassing();
        Optional<PressureRates> rates = sources.pressureRates();
        if (passing.isEmpty() || rates.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Players.Player> register = registerAt(position);
        List<String> unmatched = unmatched(rates.get().passers().stream()
                .map(passer -> new PfrEntry(passer.pfrId(), passer.player())));
        List<Ranking.Measurement> measurements = rates.get().passers().stream()
                .filter(passer -> register.containsKey(passer.pfrId()))
                .map(passer -> {
                    Players.Player player = register.get(passer.pfrId());
                    return new Ranking.Measurement(player.gsisId(), player.name(),
                            passer.rate(), passer.dropbacks());
                })
                .toList();
        IntSummaryStatistics weeks = passing.get().rows().stream()
                .filter(line -> register.containsKey(line.pfrId()))
                .mapToInt(PfrPassing.PasserLine::week)
                .summaryStatistics();
        return span(weeks, rates.get().season(), rates.get().priorSeasonFinal())
                .map(span -> Ranking.rank(PlayerStat.PRESSURED_RATE, position, span,
                        measurements).withUnmatched(unmatched));
    }

    private Optional<Ranking> fromTable(DefenseStat stat, Position position,
            Function<DefenseVersusPosition.TeamDefense, DefenseVersusPosition.Allowed> allowed) {
        return store.defenseVersusPosition()
                .filter(table -> table.throughWeek() != null)
                .map(table -> {
                    List<Ranking.Measurement> measurements = new ArrayList<>();
                    table.byTeam().forEach((team, defense) -> {
                        DefenseVersusPosition.Allowed figure = allowed.apply(defense);
                        measurements.add(new Ranking.Measurement(team, team,
                                figure == null ? null : figure.perGame(), defense.games()));
                    });
                    return Ranking.rank(stat, position, new Ranking.Weeks(table.season(),
                            table.priorSeasonFinal(), 1, table.throughWeek()), measurements);
                });
    }

    private Optional<Ranking> contactAllowed(Position position) {
        return store.rushingAdvancedStats().flatMap(stats -> {
            Map<String, List<RushingAdvancedStats.RushLine>> byDefense = stats.rows().stream()
                    .collect(Collectors.groupingBy(RushingAdvancedStats.RushLine::opponent));
            List<Ranking.Measurement> measurements = new ArrayList<>();
            byDefense.forEach((team, lines) -> {
                measurements.add(new Ranking.Measurement(team, team,
                        Ratio.of(yardsBeforeContact(lines), carries(lines)),
                        (int) lines.stream().map(RushingAdvancedStats.RushLine::week)
                                .distinct().count()));
            });
            IntSummaryStatistics weeks = stats.rows().stream()
                    .mapToInt(RushingAdvancedStats.RushLine::week).summaryStatistics();
            return span(weeks, stats.season(), stats.priorSeasonFinal())
                    .map(span -> Ranking.rank(DefenseStat.YARDS_BEFORE_CONTACT_ALLOWED,
                            position, span, measurements));
        });
    }

    private Optional<Ranking> pressureRate(Position position) {
        return store.pfrDefense().flatMap(defense -> sources.pressureRates().flatMap(rates -> {
            List<Ranking.Measurement> measurements = rates.defenses().stream()
                    .map(rate -> new Ranking.Measurement(rate.team(), rate.team(),
                            rate.rate(), rate.games()))
                    .toList();
            IntSummaryStatistics weeks = defense.rows().stream()
                    .mapToInt(PfrDefense.GamePressures::week).summaryStatistics();
            return span(weeks, rates.season(), rates.priorSeasonFinal())
                    .map(span -> Ranking.rank(DefenseStat.PRESSURE_RATE, position, span,
                            measurements));
        }));
    }

    private Optional<Ranking> blitzRate(Position position) {
        return sources.blitzRates().flatMap(blitz -> {
            List<Ranking.Measurement> measurements = blitz.defenses().stream()
                    .map(rate -> new Ranking.Measurement(rate.team(), rate.team(),
                            rate.rate(), rate.dropbacks()))
                    .toList();
            IntSummaryStatistics weeks = blitz.weeks().stream()
                    .mapToInt(Integer::intValue).summaryStatistics();
            return span(weeks, blitz.season(), blitz.priorSeasonFinal())
                    .map(span -> Ranking.rank(DefenseStat.BLITZ_RATE, position, span,
                            measurements));
        });
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

    /** Returns the register's players at the position, by PFR id. */
    private Map<String, Players.Player> registerAt(Position position) {
        Map<String, Players.Player> byPfr = new LinkedHashMap<>();
        store.players().ifPresent(players -> players.byPfrId().forEach((pfrId, player) -> {
            if (position.name().equals(player.position())) {
                byPfr.put(pfrId, player);
            }
        }));
        return byPfr;
    }

    private static Optional<Ranking.Weeks> span(IntSummaryStatistics weeks, String season,
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

    private static int carries(List<RushingAdvancedStats.RushLine> lines) {
        return lines.stream().mapToInt(RushingAdvancedStats.RushLine::carries).sum();
    }

    private static int yardsBeforeContact(List<RushingAdvancedStats.RushLine> lines) {
        return lines.stream().mapToInt(RushingAdvancedStats.RushLine::yardsBeforeContact).sum();
    }
}
