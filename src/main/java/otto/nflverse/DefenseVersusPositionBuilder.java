package otto.nflverse;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import otto.alerts.SelfReportService;
import otto.lineup.LeagueScoring;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;

/**
 * The nightly build of the defense-versus-position table: every
 * regular-season stat line so far, grouped by the defense that allowed
 * it and priced in the league's own scoring.
 *
 * The job writes nothing when it cannot see the weekly stats. A stale
 * table the user can be told the age of beats an empty one that reads
 * as "every defense is equal".
 */
@Component
public class DefenseVersusPositionBuilder {

    /** The Sleeper stat key the rushing-yards column lands under. */
    private static final String RUSHING_YARDS = "rush_yd";

    private static final Logger log = LoggerFactory.getLogger(DefenseVersusPositionBuilder.class);

    private final NflverseStore store;
    private final SleeperAdapter sleeper;
    private final SelfReportService selfReport;
    private final Clock clock;

    public DefenseVersusPositionBuilder(NflverseStore store, SleeperAdapter sleeper,
            SelfReportService selfReport, Clock clock) {
        this.store = store;
        this.sleeper = sleeper;
        this.selfReport = selfReport;
        this.clock = clock;
    }

    /** @return the table just built, or empty when a source is missing */
    public Optional<DefenseVersusPosition> build() {
        Optional<WeeklyStats> stats = store.weeklyStats();
        if (stats.isEmpty()) {
            selfReport.report("nflverse:stats_player",
                    "no weekly player stats stored yet, so I cannot rank defenses");
            return Optional.empty();
        }

        SourceResult<SleeperAdapter.League> league = sleeper.league();
        selfReport.reportIfUnavailable(league);
        if (!(league instanceof SourceResult.Ok<SleeperAdapter.League> leagueOk)) {
            return Optional.empty();
        }
        LeagueScoring scoring = new LeagueScoring(leagueOk.value().scoringSettings());
        if (scoring.isEmpty()) {
            selfReport.report("sleeper:league-settings",
                    "league document lacks scoring_settings, so points allowed cannot be priced");
            return Optional.empty();
        }

        DefenseVersusPosition table = rank(stats.get(), scoring, clock.instant());
        store.writeDefenseVersusPosition(table);
        log.info("Defense-versus-position table built: {} defenses, {}",
                table.teams(), table.basis());
        return Optional.of(table);
    }

    private DefenseVersusPosition rank(WeeklyStats stats, LeagueScoring scoring, Instant now) {
        Map<String, Set<Integer>> weeksFaced = new HashMap<>();
        Map<String, Map<String, Double>> pointsAllowed = new HashMap<>();
        Map<String, Double> rushingAllowed = new HashMap<>();
        int latestWeek = 0;

        for (WeeklyStats.StatLine line : stats.rows()) {
            String defense = line.opponent();
            if (defense == null || defense.isBlank()) {
                continue;
            }
            latestWeek = Math.max(latestWeek, line.week());
            weeksFaced.computeIfAbsent(defense, team -> new HashSet<>()).add(line.week());
            pointsAllowed
                    .computeIfAbsent(defense, team -> new HashMap<>())
                    .merge(line.position(),
                            scoring.price(line.stats(), line.position()).orElse(0.0), Double::sum);
            rushingAllowed.merge(defense, line.stats().getOrDefault(RUSHING_YARDS, 0.0),
                    Double::sum);
        }

        Map<String, Integer> games = new HashMap<>();
        weeksFaced.forEach((defense, weeks) -> games.put(defense, weeks.size()));

        Map<String, Map<String, Double>> perGame = new HashMap<>();
        pointsAllowed.forEach((defense, byPosition) -> {
            Map<String, Double> rates = new HashMap<>();
            byPosition.forEach((position, total) -> rates.put(position, total / games.get(defense)));
            perGame.put(defense, rates);
        });
        Map<String, Double> rushingPerGame = new HashMap<>();
        rushingAllowed.forEach((defense, total) ->
                rushingPerGame.put(defense, total / games.get(defense)));

        Map<String, Map<String, Integer>> ranks = rankByPosition(perGame);
        Map<String, Integer> rushingRanks = rankOf(rushingPerGame);

        Map<String, DefenseVersusPosition.TeamDefense> byTeam = new LinkedHashMap<>();
        perGame.keySet().stream().sorted().forEach(defense -> {
            Map<String, DefenseVersusPosition.Allowed> byPosition = new LinkedHashMap<>();
            perGame.get(defense).forEach((position, rate) -> byPosition.put(position,
                    new DefenseVersusPosition.Allowed(rate, ranks.get(position).get(defense))));
            byTeam.put(defense, new DefenseVersusPosition.TeamDefense(
                    games.get(defense),
                    byPosition,
                    new DefenseVersusPosition.Allowed(rushingPerGame.get(defense),
                            rushingRanks.get(defense))));
        });

        return new DefenseVersusPosition(
                stats.season(),
                basis(stats, latestWeek),
                now,
                byTeam.size(),
                byTeam);
    }

    private static String basis(WeeklyStats stats, int latestWeek) {
        if (stats.priorSeasonFinal()) {
            return "%s final".formatted(stats.season());
        }
        return "%s season to date through week %d".formatted(stats.season(), latestWeek);
    }

    /** Ranks defenses within each position: 1 allows the fewest points. */
    private static Map<String, Map<String, Integer>> rankByPosition(
            Map<String, Map<String, Double>> perGame) {
        Map<String, Map<String, Double>> byPosition = new HashMap<>();
        perGame.forEach((defense, rates) -> rates.forEach((position, rate) ->
                byPosition.computeIfAbsent(position, key -> new HashMap<>()).put(defense, rate)));

        Map<String, Map<String, Integer>> ranks = new HashMap<>();
        byPosition.forEach((position, rates) -> ranks.put(position, rankOf(rates)));
        return ranks;
    }

    /**
     * Fewest allowed ranks first. Ties break on the team code so that
     * two identical defenses always rank the same way round, which
     * keeps a nightly rebuild from reshuffling the table for no reason.
     */
    private static Map<String, Integer> rankOf(Map<String, Double> perGame) {
        List<String> order = new ArrayList<>(perGame.keySet());
        order.sort(Comparator.comparingDouble((String team) -> perGame.get(team))
                .thenComparing(Comparator.naturalOrder()));
        Map<String, Integer> ranks = new HashMap<>();
        for (int position = 0; position < order.size(); position++) {
            ranks.put(order.get(position), position + 1);
        }
        return ranks;
    }
}
