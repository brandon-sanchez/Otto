package otto.check;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.alerts.AlertService;
import otto.alerts.SelfReportService;
import otto.directory.PlayerDirectoryService;
import otto.directory.PlayerDirectoryStore;
import otto.events.Event;
import otto.events.EventLog;
import otto.lineup.GameWeek;
import otto.lineup.ProjectionTable;
import otto.lineup.Slot;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;
import otto.snapshot.LeagueStatus;
import otto.snapshot.Snapshot;
import otto.snapshot.SnapshotBuilder;
import otto.snapshot.SnapshotDiffer;
import otto.snapshot.SnapshotStore;

/**
 * One Check: the deterministic pipeline entry point. Scheduled in
 * production; invoked directly by wire-seam tests.
 */
@Component
public class CheckRunner {

    private final PlayerDirectoryService directoryService;
    private final PlayerDirectoryStore directoryStore;
    private final SleeperAdapter sleeper;
    private final SnapshotBuilder snapshotBuilder;
    private final SnapshotDiffer snapshotDiffer;
    private final SnapshotStore snapshotStore;
    private final LastCheckStore stateStore;
    private final EventLog eventLog;
    private final AlertService alertService;
    private final SelfReportService selfReport;
    private final Clock clock;
    private final Duration preDraftCheckInterval;

    public CheckRunner(PlayerDirectoryService directoryService,
            PlayerDirectoryStore directoryStore, SleeperAdapter sleeper,
            SnapshotBuilder snapshotBuilder, SnapshotDiffer snapshotDiffer,
            SnapshotStore snapshotStore, LastCheckStore stateStore, EventLog eventLog,
            AlertService alertService, SelfReportService selfReport, Clock clock,
            OttoProperties properties) {
        this.directoryService = directoryService;
        this.directoryStore = directoryStore;
        this.sleeper = sleeper;
        this.snapshotBuilder = snapshotBuilder;
        this.snapshotDiffer = snapshotDiffer;
        this.snapshotStore = snapshotStore;
        this.stateStore = stateStore;
        this.eventLog = eventLog;
        this.alertService = alertService;
        this.selfReport = selfReport;
        this.clock = clock;
        this.preDraftCheckInterval = properties.preDraftCheckInterval();
    }

    public CheckResult runCheck() {
        Instant now = clock.instant();
        Optional<LastCheck> state = stateStore.read();
        if (withinPreDraftCadence(state, now)) {
            return CheckResult.skippedByCadence();
        }

        PlayerDirectoryService.Update directory = directoryService.updateIfDue();
        if (directory instanceof PlayerDirectoryService.Update.Unavailable unavailable) {
            selfReport.report(unavailable.source(), unavailable.reason());
        }

        SnapshotStage stage = snapshotStage(now);
        // Roster Alerts fire only in season: pre-draft and drafting stay quiet.
        List<Event> alerts = stage.snapshot()
                .filter(snapshot -> snapshot.leagueStatus() == LeagueStatus.IN_SEASON)
                .map(snapshot -> alertService.process(snapshot, weekFacts(stage.league())))
                .orElseGet(List::of);

        LeagueStatus leagueStatus = stage.snapshot().map(Snapshot::leagueStatus)
                .or(() -> state.map(LastCheck::leagueStatus))
                .orElse(LeagueStatus.UNKNOWN);
        stateStore.write(new LastCheck(now, leagueStatus));
        return new CheckResult(false, directory, stage.newDiffEvents(), alerts);
    }

    private boolean withinPreDraftCadence(Optional<LastCheck> state, Instant now) {
        return state.isPresent()
                && state.get().leagueStatus() == LeagueStatus.PRE_DRAFT
                && Duration.between(state.get().lastCheckAt(), now)
                        .compareTo(preDraftCheckInterval) < 0;
    }

    private record SnapshotStage(Optional<Snapshot> snapshot, SleeperAdapter.League league,
            List<Event> newDiffEvents) {
    }

    private SnapshotStage snapshotStage(Instant now) {
        SourceResult<SleeperAdapter.League> league = sleeper.league();
        SourceResult<List<SleeperAdapter.Roster>> rosters = sleeper.rosters();
        SourceResult<List<SleeperAdapter.LeagueUser>> users = sleeper.users();
        reportIfUnavailable(league);
        reportIfUnavailable(rosters);
        reportIfUnavailable(users);
        if (!(league instanceof SourceResult.Ok<SleeperAdapter.League> leagueOk)
                || !(rosters instanceof SourceResult.Ok<List<SleeperAdapter.Roster>> rostersOk)
                || !(users instanceof SourceResult.Ok<List<SleeperAdapter.LeagueUser>> usersOk)) {
            return new SnapshotStage(Optional.empty(), null, List.of());
        }

        Snapshot current = snapshotBuilder.build(now, leagueOk.value(),
                rostersOk.value(), usersOk.value(), directoryStore.read());
        Optional<Snapshot> previous = snapshotStore.advance(current);

        List<Event> appended = new ArrayList<>();
        for (Event event : snapshotDiffer.diff(previous, current, now)) {
            if (eventLog.append(event)) {
                appended.add(event);
            }
        }
        return new SnapshotStage(Optional.of(current), leagueOk.value(), appended);
    }

    /**
     * Assembles the week-scoped detection inputs: the NFL week, the
     * projected stat lines priced in league scoring, the starting
     * slots, and the week's games. Every failed source self-reports
     * once and leaves its part empty - detectors skip what is missing.
     */
    private WeekFacts weekFacts(SleeperAdapter.League league) {
        List<Slot> slots = Slot.startingSlots(league.rosterPositions());
        if (slots.isEmpty() || league.scoringSettings().isEmpty()) {
            selfReport.report("sleeper:league-settings",
                    "league document lacks roster_positions or scoring_settings");
        }

        SourceResult<SleeperAdapter.NflState> state = sleeper.nflState();
        reportIfUnavailable(state);
        if (!(state instanceof SourceResult.Ok<SleeperAdapter.NflState> stateOk)) {
            return WeekFacts.unavailable(slots);
        }
        String season = stateOk.value().season();
        int week = stateOk.value().week();

        // A projection table without scoring settings could only answer
        // "no projection available"; stay empty so detectors skip instead.
        SourceResult<Map<String, Map<String, Double>>> projections =
                sleeper.projections(season, week);
        reportIfUnavailable(projections);
        Optional<ProjectionTable> projectionTable = !league.scoringSettings().isEmpty()
                && projections
                        instanceof SourceResult.Ok<Map<String, Map<String, Double>>> projectionsOk
                                ? Optional.of(new ProjectionTable(
                                        league.scoringSettings(), projectionsOk.value()))
                                : Optional.empty();

        SourceResult<List<SleeperAdapter.Game>> games = sleeper.games(season, week);
        reportIfUnavailable(games);
        Optional<GameWeek> gameWeek =
                games instanceof SourceResult.Ok<List<SleeperAdapter.Game>> gamesOk
                        ? Optional.of(GameWeek.of(gamesOk.value()))
                        : Optional.empty();

        return new WeekFacts(
                Optional.of("%s-w%d".formatted(season, week)),
                projectionTable,
                slots,
                gameWeek);
    }

    private void reportIfUnavailable(SourceResult<?> result) {
        if (result instanceof SourceResult.Unavailable<?> unavailable) {
            selfReport.report(unavailable.source(), unavailable.reason());
        }
    }
}
