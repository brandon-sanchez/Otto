package otto.check;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.alerts.SelfReportService;
import otto.lineup.GameWeek;
import otto.lineup.LeagueScoring;
import otto.lineup.ProjectionTable;
import otto.lineup.Slot;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SleeperSources;
import otto.sleeper.SourceResult;

/**
 * Assembles the week-scoped facts: the NFL week, the projected stat
 * lines priced in league scoring, the starting slots, and the week's
 * games. Every failed source self-reports once and leaves its part
 * empty - readers skip what is missing instead of guessing.
 *
 * A Check builds these for Alert detection; the Ask tools build the
 * same facts to answer questions, so both lanes read one week the same
 * way. They differ only in how they reach the wire: the Check polls,
 * and an Ask answers from the copy the Check left behind while it is
 * inside its cadence interval.
 */
@Component
public class WeekFactsBuilder {

    private final SleeperAdapter polling;
    private final SleeperAdapter withinCadence;
    private final SelfReportService selfReport;

    public WeekFactsBuilder(SleeperAdapter sleeper, SelfReportService selfReport,
            OttoProperties properties) {
        this.polling = sleeper;
        this.withinCadence = sleeper.cachedWithin(properties.sleeperCadence());
        this.selfReport = selfReport;
    }

    /** The Check's read: poll, because polling is what a Check is. */
    public WeekFacts build(SleeperAdapter.League league) {
        return build(league, polling, OptionalInt.empty());
    }

    /**
     * An Ask's read: the stored copy answers while it is younger than
     * the cadence interval, and only an older one costs a request.
     */
    public WeekFacts buildWithinCadence(SleeperAdapter.League league) {
        return build(league, withinCadence, OptionalInt.empty());
    }

    /**
     * An Ask's read of another week of the same season, such as next
     * week's lineup: that week's projections and games, read the same
     * way as this week's.
     *
     * @param week the regular-season week number to read
     */
    public WeekFacts buildWithinCadence(SleeperAdapter.League league, int week) {
        return build(league, withinCadence, OptionalInt.of(week));
    }

    private WeekFacts build(SleeperAdapter.League league, SleeperAdapter sleeper,
            OptionalInt namedWeek) {
        List<Slot> slots = Slot.startingSlots(league.rosterPositions());
        LeagueScoring scoring = new LeagueScoring(league.scoringSettings());
        if (slots.isEmpty() || scoring.isEmpty()) {
            selfReport.report(SleeperSources.LEAGUE_SETTINGS,
                    "league document lacks roster_positions or scoring_settings");
        }

        SourceResult<SleeperAdapter.NflState> state = sleeper.nflState();
        selfReport.reportIfUnavailable(state);
        if (!(state instanceof SourceResult.Ok<SleeperAdapter.NflState> stateOk)) {
            return WeekFacts.unavailable(scoring, slots);
        }
        String season = stateOk.value().season();
        int week = namedWeek.orElse(stateOk.value().week());

        // A projection table without scoring settings could only answer
        // "no projection available"; stay empty so readers skip instead.
        SourceResult<Map<String, Map<String, Double>>> projections =
                sleeper.projections(season, week);
        selfReport.reportIfUnavailable(projections);
        Optional<ProjectionTable> projectionTable = !scoring.isEmpty()
                && projections
                        instanceof SourceResult.Ok<Map<String, Map<String, Double>>> projectionsOk
                                ? Optional.of(new ProjectionTable(scoring, projectionsOk.value()))
                                : Optional.empty();

        SourceResult<List<SleeperAdapter.Game>> games = sleeper.games(season, week);
        selfReport.reportIfUnavailable(games);
        Optional<GameWeek> gameWeek =
                games instanceof SourceResult.Ok<List<SleeperAdapter.Game>> gamesOk
                        ? Optional.of(GameWeek.of(gamesOk.value()))
                        : Optional.empty();

        return new WeekFacts(
                Optional.of(new SeasonWeek(season, week, stateOk.value().seasonType(),
                        stateOk.value().seasonStart())),
                scoring,
                projectionTable,
                slots,
                gameWeek);
    }
}
