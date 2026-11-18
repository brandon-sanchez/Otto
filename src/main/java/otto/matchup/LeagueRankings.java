package otto.matchup;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import otto.nflverse.DefenseVersusPosition;
import otto.nflverse.NflverseStore;
import otto.nflverse.PfrDefense;
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
 * identified by gsis id, and every player with a measured value is
 * ranked, however small his sample, so a rank reads from week 1. A
 * player read from a PFR file is placed at a position through the
 * nflverse player register, and one the register does not list is left
 * out of every population, named in {@link Ranking#unmatched()} and
 * logged.
 */
public final class LeagueRankings {

    /** The deepest rank among every position that is still worth quoting. */
    static final int NOTABLE_OVERALL = 10;

    private final MatchupSources sources;
    private final NflverseStore store;
    private final PlayerMeasures measures;
    private final Map<RankingKey, Optional<Ranking>> ranked = new HashMap<>();
    private final Map<MeasureKey, Optional<PlayerMeasures.Measured>> measuredPlayers =
            new HashMap<>();

    LeagueRankings(MatchupSources sources) {
        this.sources = sources;
        this.store = sources.store();
        this.measures = new PlayerMeasures(sources);
    }

    /**
     * @param position null for a ranking across every position
     * @param span the weeks a player ranking covers; null for a defense or schedule
     */
    private record RankingKey(RankedStat stat, Position position, WeekSpan span) {
    }

    private record MeasureKey(PlayerStat stat, WeekSpan span) {
    }

    /**
     * Ranks every player at the position on the stat, season to date.
     *
     * @param position the position the players play
     * @return empty when no stored source holds the stat for any week
     */
    public Optional<Ranking> players(PlayerStat stat, Position position) {
        return players(stat, position, WeekSpan.SEASON);
    }

    /**
     * Ranks every player at the position on the stat over the span.
     *
     * @param position the position the players play
     * @return empty when no stored source holds the stat in the span, or
     *         the stat is measured over the season only, as
     *         {@link PlayerStat#spansWeeks()} says
     */
    public Optional<Ranking> players(PlayerStat stat, Position position, WeekSpan span) {
        return ranked.computeIfAbsent(new RankingKey(stat, position, span),
                key -> measured(stat, span).map(measured -> Ranking.rank(stat, position,
                        measured.weeks(), measured.at(Set.of(position)))
                        .withUnmatched(measured.unmatched())));
    }

    /**
     * Ranks every quarterback, running back, receiver and tight end
     * together on the stat, season to date, for "3rd among all players
     * in targets".
     *
     * @return empty when no stored source holds the stat for any week
     */
    public Optional<Ranking> allPlayers(PlayerStat stat) {
        return allPlayers(stat, WeekSpan.SEASON);
    }

    /**
     * Ranks every quarterback, running back, receiver and tight end
     * together on the stat over the span.
     *
     * @return empty when no stored source holds the stat in the span, or
     *         the stat is measured over the season only
     */
    public Optional<Ranking> allPlayers(PlayerStat stat, WeekSpan span) {
        return ranked.computeIfAbsent(new RankingKey(stat, null, span),
                key -> measured(stat, span).map(measured -> Ranking.rankAll(stat,
                        measured.weeks(), measured.at(EnumSet.allOf(Position.class)))
                        .withUnmatched(measured.unmatched())));
    }

    /** Ranks only the requested positions together, season to date. */
    public Optional<Ranking> players(PlayerStat stat, Set<Position> positions) {
        if (positions.isEmpty()) {
            return Optional.empty();
        }
        if (positions.size() == 1) {
            return players(stat, positions.iterator().next());
        }
        if (positions.size() == Position.values().length) {
            return allPlayers(stat);
        }
        return measured(stat, WeekSpan.SEASON).map(measured -> Ranking.rankGroup(stat,
                RankWords.group(positions), measured.weeks(), measured.at(positions))
                .withUnmatched(measured.unmatched()));
    }

    /**
     * Returns the position each measured player plays, by gsis id, so an
     * all-players ranking can say who plays where.
     */
    public Map<String, Position> positions(PlayerStat stat, WeekSpan span) {
        return measured(stat, span)
                .map(measured -> measured.players().stream().collect(Collectors.toMap(
                        placed -> placed.measurement().id(), PlayerMeasures.Placed::position,
                        (first, _) -> first)))
                .orElse(Map.of());
    }

    /**
     * Returns where the player ranks among every position on the stat,
     * season to date, in the words {@link RankWords#overall} writes, only
     * when that is worth saying beside his rank at his position: when he
     * is in the league's top {@value #NOTABLE_OVERALL}.
     *
     * @param position the position he plays
     */
    public Optional<String> notableOverall(PlayerStat stat, String gsisId, Position position) {
        return allPlayers(stat).flatMap(all -> all.placement(gsisId)
                .filter(placement -> placement.rank() <= NOTABLE_OVERALL)
                .map(placement -> RankWords.overall(all, placement, position)));
    }

    private Optional<PlayerMeasures.Measured> measured(PlayerStat stat, WeekSpan span) {
        return measuredPlayers.computeIfAbsent(new MeasureKey(stat, span),
                key -> measures.measure(stat, span));
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
        return ranked.computeIfAbsent(new RankingKey(stat, position, null), key -> switch (stat) {
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
        return ranked.computeIfAbsent(new RankingKey(stat, position, null), key -> sources
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

    private static Optional<Ranking.Weeks> span(IntSummaryStatistics weeks, String season,
            boolean priorSeasonFinal) {
        return weeks.getCount() == 0
                ? Optional.empty()
                : Optional.of(new Ranking.Weeks(season, priorSeasonFinal, weeks.getMin(),
                        weeks.getMax()));
    }

    private static int carries(List<RushingAdvancedStats.RushLine> lines) {
        return lines.stream().mapToInt(RushingAdvancedStats.RushLine::carries).sum();
    }

    private static int yardsBeforeContact(List<RushingAdvancedStats.RushLine> lines) {
        return lines.stream().mapToInt(RushingAdvancedStats.RushLine::yardsBeforeContact).sum();
    }
}
