package otto.ask;

import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.alerts.SelfReportService;
import otto.check.WeekFactsBuilder;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;
import otto.snapshot.Snapshot;
import otto.snapshot.SnapshotStore;

/**
 * Loads what the Ask tools read: the league document plus the user's
 * roster from the latest Snapshot. Asks never poll a new Snapshot -
 * the Check owns that cadence, and answering from the stored one keeps
 * a burst of questions off Sleeper's rate limit.
 */
@Component
public class UserWeekLoader {

    private final SleeperAdapter sleeper;
    private final SnapshotStore snapshotStore;
    private final WeekFactsBuilder weekFactsBuilder;
    private final SelfReportService selfReport;

    public UserWeekLoader(SleeperAdapter sleeper, SnapshotStore snapshotStore,
            WeekFactsBuilder weekFactsBuilder, SelfReportService selfReport) {
        this.sleeper = sleeper;
        this.snapshotStore = snapshotStore;
        this.weekFactsBuilder = weekFactsBuilder;
        this.selfReport = selfReport;
    }

    /** The league document alone, for questions that need no roster. */
    public SourceResult<SleeperAdapter.League> league() {
        SourceResult<SleeperAdapter.League> league = sleeper.league();
        selfReport.reportIfUnavailable(league);
        return league;
    }

    public SourceResult<UserWeek> load() {
        return league().flatMap(league -> userRoster()
                .<SourceResult<UserWeek>>map(roster -> new SourceResult.Ok<>(
                        new UserWeek(league, roster, weekFactsBuilder.build(league))))
                .orElseGet(() -> new SourceResult.Unavailable<>("snapshot",
                        "no Snapshot stored yet, so I cannot see your roster; "
                                + "the next Check builds one")));
    }

    private Optional<RosterSnapshot> userRoster() {
        return snapshotStore.current()
                .map(Snapshot::rosters)
                .flatMap(rosters -> rosters.stream()
                        .filter(RosterSnapshot::userRoster)
                        .findFirst());
    }
}
