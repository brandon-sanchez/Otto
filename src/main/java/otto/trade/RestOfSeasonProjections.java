package otto.trade;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.alerts.SelfReportService;
import otto.ask.LeagueWeek;
import otto.lineup.GameWeek;
import otto.lineup.LeagueScoring;
import otto.lineup.ProjectionTable;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;

/**
 * Rest-of-season projected points, built one remaining week at a time.
 *
 * <p>Sleeper publishes projections and a schedule per week and nothing
 * for a season, so a season total is a sum of weeks. That is also the
 * only shape that can count a bye as zero: a bye is a week the player's
 * team has no game in, and only the week's own schedule says so.
 *
 * <p>The reads go through the cadence-gated adapter, so a second
 * question inside the cadence interval costs nothing. Every week fails
 * soft on its own: a week whose projections or schedule never arrived
 * is left out of the total and named in the notes, rather than counted
 * as zero and read as a bye.
 */
@Component
public class RestOfSeasonProjections {

    /** The last week an NFL regular season can reach. */
    private static final int LAST_POSSIBLE_WEEK = 18;

    private final SleeperAdapter sleeper;
    private final SelfReportService selfReport;

    public RestOfSeasonProjections(SleeperAdapter sleeper, SelfReportService selfReport,
            OttoProperties properties) {
        this.sleeper = sleeper.cachedWithin(properties.sleeperCadence());
        this.selfReport = selfReport;
    }

    /**
     * The remaining regular season priced for every player named.
     *
     * @param positions the position of each player to price, which is
     *        what tells the scoring how to price his stat line
     */
    public SourceResult<RestOfSeason> restOfSeason(LeagueWeek league,
            Map<String, String> positions, Map<String, String> teams) {
        SourceResult<SleeperAdapter.NflState> state = sleeper.nflState();
        selfReport.reportIfUnavailable(state);
        if (!(state instanceof SourceResult.Ok<SleeperAdapter.NflState> stateOk)) {
            return new SourceResult.Unavailable<>("sleeper:state",
                    "I cannot see which NFL week it is, so I cannot price the rest of the "
                            + "season");
        }
        if (league.lastRegularWeek().isEmpty()) {
            return new SourceResult.Unavailable<>("sleeper:league",
                    "the league document does not say when the playoffs start, so I cannot "
                            + "tell how much of the regular season is left");
        }
        LeagueScoring scoring = new LeagueScoring(league.league().scoringSettings());
        if (scoring.isEmpty()) {
            return new SourceResult.Unavailable<>("sleeper:league-settings",
                    "the league document carries no scoring settings, so I can price nothing");
        }

        String season = stateOk.value().season();
        int firstWeek = stateOk.value().week();
        // A drifted playoff_week_start must not turn one question into
        // hundreds of requests. No NFL regular season runs past week 18.
        int lastWeek = Math.min(league.lastRegularWeek().getAsInt(), LAST_POSSIBLE_WEEK);
        if (firstWeek > lastWeek) {
            return new SourceResult.Unavailable<>("sleeper:state",
                    "the regular season ended in week %d, so there is no season left to price"
                            .formatted(lastWeek));
        }

        Map<String, Double> points = new HashMap<>();
        Map<String, List<Integer>> byeWeeks = new LinkedHashMap<>();
        Map<String, Integer> weeksPriced = new HashMap<>();
        List<Integer> weeks = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        for (int week = firstWeek; week <= lastWeek; week++) {
            Optional<ProjectionTable> table = weekTable(scoring, season, week);
            Optional<GameWeek> games = weekGames(season, week);
            if (table.isEmpty() || games.isEmpty()) {
                notes.add("Week %d is missing its %s, so it counts towards nothing".formatted(
                        week, table.isEmpty() ? "projections" : "schedule"));
                continue;
            }
            weeks.add(week);
            for (Map.Entry<String, String> player : positions.entrySet()) {
                String playerId = player.getKey();
                if (games.get().onBye(teams.get(playerId))) {
                    byeWeeks.computeIfAbsent(playerId, id -> new ArrayList<>()).add(week);
                    points.merge(playerId, 0.0, Double::sum);
                    weeksPriced.merge(playerId, 1, Integer::sum);
                    continue;
                }
                Optional<Double> projected = table.get().points(playerId, player.getValue());
                if (projected.isEmpty()) {
                    continue;
                }
                points.merge(playerId, projected.get(), Double::sum);
                weeksPriced.merge(playerId, 1, Integer::sum);
            }
        }

        if (weeks.isEmpty()) {
            return new SourceResult.Unavailable<>("sleeper:projections",
                    "no week left in the season carries both projections and a schedule, so I "
                            + "can price no player");
        }
        notes.add(("Rest of season is weeks %d to %d, priced in this league's scoring, with a "
                + "bye week counting zero").formatted(weeks.getFirst(), weeks.getLast()));
        Map<String, List<Integer>> byes = new LinkedHashMap<>();
        byeWeeks.forEach((playerId, weeksOff) -> byes.put(playerId, List.copyOf(weeksOff)));
        return new SourceResult.Ok<>(new RestOfSeason(List.copyOf(weeks), Map.copyOf(points),
                Map.copyOf(byes), Map.copyOf(weeksPriced), List.copyOf(notes)));
    }

    private Optional<ProjectionTable> weekTable(LeagueScoring scoring, String season, int week) {
        SourceResult<Map<String, Map<String, Double>>> projections =
                sleeper.projections(season, week);
        selfReport.reportIfUnavailable(projections);
        return projections instanceof SourceResult.Ok<Map<String, Map<String, Double>>> ok
                ? Optional.of(new ProjectionTable(scoring, ok.value()))
                : Optional.empty();
    }

    private Optional<GameWeek> weekGames(String season, int week) {
        SourceResult<List<SleeperAdapter.Game>> games = sleeper.games(season, week);
        selfReport.reportIfUnavailable(games);
        return games instanceof SourceResult.Ok<List<SleeperAdapter.Game>> ok
                ? Optional.of(GameWeek.of(ok.value()))
                : Optional.empty();
    }
}
