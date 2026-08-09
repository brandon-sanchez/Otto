package otto.ask;

import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.alerts.SelfReportService;
import otto.check.WeekFacts;
import otto.check.WeekFactsBuilder;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;
import otto.snapshot.Snapshot;
import otto.snapshot.SnapshotStore;

/**
 * Loads what the Ask tools read: the league document, this week's
 * facts, and - for questions about the user's own team - his roster
 * from the latest Snapshot. Asks never poll a new Snapshot: the Check
 * owns that cadence, and answering from the stored one keeps a burst
 * of questions off Sleeper's rate limit.
 *
 * The same restraint applies to the league document and the week: they
 * come from the copy the Check left in the cache while it is younger
 * than the cadence interval, so an ordinary question costs no requests
 * at all.
 */
@Component
public class UserWeekLoader {

    private final SleeperAdapter sleeper;
    private final SnapshotStore snapshotStore;
    private final WeekFactsBuilder weekFactsBuilder;
    private final SelfReportService selfReport;

    public UserWeekLoader(SleeperAdapter sleeper, SnapshotStore snapshotStore,
            WeekFactsBuilder weekFactsBuilder, SelfReportService selfReport,
            OttoProperties properties) {
        this.sleeper = sleeper.cachedWithin(properties.sleeperCadence());
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

    /**
     * This week's facts without a roster: the week, the projections
     * priced in league scoring, and the week's games. Questions about
     * players the user does not roster stop here.
     */
    public SourceResult<WeekFacts> week() {
        return league().flatMap(league ->
                new SourceResult.Ok<>(weekFactsBuilder.buildWithinCadence(league)));
    }

    public SourceResult<UserWeek> load() {
        return league().flatMap(league -> userRoster()
                .<SourceResult<UserWeek>>map(roster -> new SourceResult.Ok<>(
                        new UserWeek(league, roster, weekFactsBuilder.buildWithinCadence(league))))
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
