package otto.team;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import otto.advice.Ordinal;
import otto.ask.LeagueWeek;
import otto.market.MarketValuation;
import otto.sleeper.LeagueRules;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;

/**
 * Labels every team in a league contending, in the middle or
 * rebuilding, with a one-line reason.
 *
 * <p>Each team is placed from 0 to 1 against the rest of the league on
 * three measures: its summed market value in the league's format, its
 * record and its points scored. Before a game is played the market is
 * all there is. As the season goes on the record and points weigh more,
 * because they show how the roster actually plays, but the market keeps
 * a third of the weight to the end, because it prices the weeks still
 * to come. The market already prices age in dynasty and injuries in
 * redraft, so neither is counted again; in a dynasty league the age of
 * a team's most valuable players appears in the reason only.
 */
@Component
public class TeamDirections {

    /** The share of the label the market keeps once the regular season is over. */
    private static final double LATE_MARKET_WEIGHT = 1.0 / 3;

    private static final double CONTENDING_LINE = 2.0 / 3;
    private static final double REBUILDING_LINE = 1.0 / 3;

    private static final int USUAL_REGULAR_SEASON_GAMES = 14;

    /** How many of a team's most valuable players its age line covers. */
    private static final int CORE_PLAYERS = 5;

    private final MarketValuation valuation;
    private final DirectionChoices choices;

    public TeamDirections(MarketValuation valuation, DirectionChoices choices) {
        this.valuation = valuation;
        this.choices = choices;
    }

    /**
     * Labels every team that holds a player, the user's own choice taking
     * the place of his measured label.
     *
     * <p>Side effects: the market value reads of
     * {@link MarketValuation#rostered}, and a read of the user's stored
     * choice.
     *
     * @return the labels, or unavailable when no game has been played and
     *         no market list can be read, which leaves nothing to measure
     */
    public SourceResult<LeagueDirections> read(LeagueWeek league, Instant now) {
        List<RosterSnapshot> teams = league.rosters().stream()
                .filter(roster -> !roster.players().isEmpty())
                .toList();
        int games = teams.stream().mapToInt(team -> team.teamRecord().games()).max().orElse(0);
        SourceResult<MarketValuation.LeagueValues> values =
                valuation.rostered(league.league(), league.rosters(), now);
        if (values instanceof SourceResult.Unavailable<MarketValuation.LeagueValues> none
                && games == 0) {
            return new SourceResult.Unavailable<>(none.source(), none.reason());
        }
        Optional<MarketValuation.LeagueValues> market =
                values instanceof SourceResult.Ok<MarketValuation.LeagueValues> ok
                        ? Optional.of(ok.value()) : Optional.empty();
        boolean dynasty = league.league().rules().format()
                .filter(LeagueRules.LeagueFormat.DYNASTY::equals).isPresent();
        double progress = Math.min(1.0, (double) games / league.lastRegularWeek()
                .orElse(USUAL_REGULAR_SEASON_GAMES));
        Measures measures = new Measures(teams, market);
        Optional<Direction> chosen = choices.chosen(league.league().leagueId());
        List<TeamDirection> directions = teams.stream()
                .map(team -> direction(team, measures, progress, dynasty, chosen))
                .sorted(Comparator.comparingInt((TeamDirection team) ->
                                team.measured().ordinal())
                        .thenComparingInt(TeamDirection::pointsRank))
                .toList();
        return new SourceResult.Ok<>(new LeagueDirections(directions,
                market.map(MarketValuation.LeagueValues::league), games));
    }

    private record Measures(List<RosterSnapshot> teams,
            Optional<MarketValuation.LeagueValues> market) {

        int recordRank(RosterSnapshot team) {
            return rank(team, Measures::winShare);
        }

        int pointsRank(RosterSnapshot team) {
            return rank(team, roster -> roster.teamRecord().pointsFor());
        }

        Optional<Integer> value(RosterSnapshot team) {
            return market.map(values -> players(team)
                    .mapToInt(id -> values.values().getOrDefault(id, 0)).sum());
        }

        Optional<Integer> valueRank(RosterSnapshot team) {
            return market.map(_ -> rank(team, roster -> value(roster).orElse(0)));
        }

        /** Returns a rank as a placing from 0 for the last team to 1 for the first. */
        double placing(int rank) {
            return teams.size() < 2 ? 1.0 : (double) (teams.size() - rank) / (teams.size() - 1);
        }

        Optional<Double> coreAge(RosterSnapshot team) {
            Map<String, Double> ages = market.map(MarketValuation.LeagueValues::ages)
                    .orElse(Map.of());
            Map<String, Integer> values = market.map(MarketValuation.LeagueValues::values)
                    .orElse(Map.of());
            List<Double> core = players(team)
                    .filter(values::containsKey)
                    .sorted(Comparator.comparing(values::get).reversed())
                    .limit(CORE_PLAYERS)
                    .flatMap(id -> Optional.ofNullable(ages.get(id)).stream())
                    .toList();
            return core.isEmpty() ? Optional.empty()
                    : Optional.of(core.stream().mapToDouble(Double::doubleValue).average()
                            .orElseThrow());
        }

        private int rank(RosterSnapshot team, ToDoubleFunction<RosterSnapshot> measure) {
            double own = measure.applyAsDouble(team);
            return 1 + (int) teams.stream()
                    .filter(other -> measure.applyAsDouble(other) > own)
                    .count();
        }

        private static double winShare(RosterSnapshot team) {
            SleeperAdapter.TeamRecord record = team.teamRecord();
            return record.games() == 0 ? 0
                    : (record.wins() + record.ties() / 2.0) / record.games();
        }

        private static Stream<String> players(RosterSnapshot team) {
            return Stream.of(team.players(), team.reserve(), team.taxi())
                    .flatMap(List::stream)
                    .distinct();
        }
    }

    private static TeamDirection direction(RosterSnapshot team, Measures measures,
            double progress, boolean dynasty, Optional<Direction> chosen) {
        int pointsRank = measures.pointsRank(team);
        Optional<Integer> valueRank = measures.valueRank(team);
        double season = (measures.placing(measures.recordRank(team))
                + measures.placing(pointsRank)) / 2;
        double marketWeight = valueRank.isEmpty() ? 0
                : 1 - (1 - LATE_MARKET_WEIGHT) * progress;
        double placing = marketWeight * valueRank.map(measures::placing).orElse(0.0)
                + (1 - marketWeight) * season;
        Direction measured = placing >= CONTENDING_LINE ? Direction.CONTENDING
                : placing <= REBUILDING_LINE ? Direction.REBUILDING
                : Direction.MIDDLE;
        TeamDirection.Basis basis = TeamDirection.Basis.MEASURED;
        Direction direction = measured;
        if (team.userRoster() && chosen.isPresent()) {
            basis = TeamDirection.Basis.CHOSEN;
            direction = chosen.get();
        } else if (team.userRoster() && dynasty && measured == Direction.REBUILDING) {
            basis = TeamDirection.Basis.AWAITING_CONFIRMATION;
            direction = Direction.MIDDLE;
        }
        String reason = reason(team.teamRecord(), pointsRank, valueRank,
                measures.teams().size(), dynasty ? measures.coreAge(team) : Optional.empty());
        return new TeamDirection(team.rosterId(), team.manager(), team.userRoster(), direction,
                measured, basis, reason, team.teamRecord(), pointsRank, measures.value(team),
                valueRank);
    }

    private static String reason(SleeperAdapter.TeamRecord record, int pointsRank,
            Optional<Integer> valueRank, int teams, Optional<Double> coreAge) {
        Optional<String> value = valueRank.map(rank -> ranked(rank, teams, "valuable roster"));
        String reason;
        if (record.games() == 0) {
            reason = "no games played yet" + value.map(roster -> ", with " + roster).orElse("");
        } else {
            reason = "%s with %s%s".formatted(standing(record),
                    ranked(pointsRank, teams, "points"),
                    value.map(roster -> " and " + roster).orElse(""));
        }
        return reason + coreAge.map(age -> ", and its most valuable players average %s years old"
                .formatted(String.format(Locale.ROOT, "%.1f", age))).orElse("");
    }

    private static String standing(SleeperAdapter.TeamRecord record) {
        return record.ties() > 0
                ? "%d-%d-%d".formatted(record.wins(), record.losses(), record.ties())
                : "%d-%d".formatted(record.wins(), record.losses());
    }

    /** Writes a rank as "the most points", "the 3rd-most points" or "the fewest points". */
    private static String ranked(int rank, int teams, String what) {
        if (rank == 1) {
            return "the most " + what;
        }
        if (rank == teams) {
            return (what.equals("points") ? "the fewest " : "the least ") + what;
        }
        return "the %s-most %s".formatted(Ordinal.of(rank), what);
    }
}
