package otto.research;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import otto.directory.PlayerDirectory;
import otto.directory.PlayerDirectoryStore;
import otto.nflverse.NflverseStore;
import otto.nflverse.Schedule;
import otto.storage.JsonStore;

/**
 * Reads the 32 teams' own injury news into the research cache, so a
 * team's ruling reaches {@link OfficialUpdate} before Sleeper changes a
 * designation. A scheduled job runs it every five minutes.
 *
 * <p>Each run reads every feed that is due (see {@link ReportWindows})
 * with a conditional GET. A new injury-report or roster-move post leads
 * to one read of the team's injury-report page, however many such posts
 * arrived; a new inactives post leads to one read of that post. Each
 * fantasy player the report lists is kept as a research item credited
 * to the team's site, with the post's link and time and Otto's own
 * wording of the facts. No article text is kept: the sites allow
 * personal use and RSS, not building a copy of their content.
 *
 * <p>Teams go in the order their news has waited: first those with a
 * post whose report is still unread, oldest post first, then the rest
 * by how long ago their feed was read. A run reads at most a few
 * reports, and on an afternoon when every team posts, a fixed order
 * would spend them on the same teams each run.
 *
 * <p>A run stops starting feeds once two minutes have passed, well
 * inside the scheduled function's timeout, and the feeds it did not
 * reach are read on the next run. The feed state is written whatever
 * happens, so the feeds a run did read are not read again.
 *
 * <p>A feed or page that fails or changes shape is skipped and reported,
 * and what the cache already held stands. A post whose report could not
 * be read is tried again on later runs, up to {@link TeamRun#MAX_REPORT_READS}
 * times.
 */
@Component
public class TeamReports {

    /** The User-Agent every team-site request sends. */
    public static final String USER_AGENT = TeamSites.USER_AGENT;

    private static final Logger log = LoggerFactory.getLogger(TeamReports.class);

    private static final String STATE = "team-reports";

    private static final int REPORT_READS_PER_RUN = 8;

    /**
     * How long a run keeps starting feeds. Each feed can take up to the
     * team-site timeout and a report read more, and 32 of them in a row
     * could outlast the five-minute function timeout, which would lose
     * the feed state of the whole run.
     */
    private static final Duration RUN_BUDGET = Duration.ofSeconds(120);

    /**
     * Runs in a row a team's feed may fail before each further failure
     * is logged as an error, which the error alarm counts. One failed
     * read is a blip the next run usually clears and is only a warning.
     */
    private static final int FAILED_RUNS_LOGGED_AS_ERROR = 3;

    private final TeamSites sites;
    private final ResearchCache cache;
    private final PlayerDirectoryStore directory;
    private final NflverseStore nflverse;
    private final JsonStore store;
    private final Clock clock;

    TeamReports(TeamSites sites, ResearchCache cache, PlayerDirectoryStore directory,
            NflverseStore nflverse, JsonStore store, Clock clock) {
        this.sites = sites;
        this.cache = cache;
        this.directory = directory;
        this.nflverse = nflverse;
        this.store = store;
        this.clock = clock;
    }

    /**
     * What one run did.
     *
     * @param feedsRead feeds that answered, changed or not
     * @param feedsUnchanged of those, feeds that answered 304 or the same
     *        body as last time
     * @param reportsRead injury pages and inactives posts read and parsed
     * @param itemsUpdated research items that were new or changed
     * @param problems each feed or page that failed or changed shape, and why
     */
    public record Run(int feedsRead, int feedsUnchanged, int reportsRead, int itemsUpdated,
            List<String> problems) {

        public Run {
            problems = List.copyOf(problems);
        }
    }

    /** What Otto remembers between runs: each feed, by team abbreviation. */
    record State(Map<String, TeamRun.FeedState> feeds) {
    }

    /**
     * Reads every due feed and the reports its new posts point to.
     *
     * <p>Side effects: an HTTP request per due feed and per report read,
     * a research cache write per player with new facts, and one write of
     * the feed state, made even when the run fails partway.
     */
    public Run poll() {
        Instant now = clock.instant();
        Optional<PlayerDirectory> players = directory.read();
        if (players.isEmpty()) {
            log.warn("team reports: no player directory yet; feeds not read");
            return new Run(0, 0, 0, 0, List.of("no player directory yet; feeds not read"));
        }
        Map<String, TeamRun.FeedState> feeds = new HashMap<>(store.read(STATE, State.class)
                .map(State::feeds)
                .orElse(Map.of()));
        List<Schedule.Game> games = nflverse.schedule().map(Schedule::rows).orElse(List.of());
        TeamRun.Tally tally = new TeamRun.Tally(REPORT_READS_PER_RUN);
        Instant stopStarting = now.plus(RUN_BUDGET);
        List<Map.Entry<String, OfficialTeams.Team>> teams = OfficialTeams.all().entrySet().stream()
                .sorted(longestWaitingFirst(feeds))
                .toList();
        try {
            for (Map.Entry<String, OfficialTeams.Team> entry : teams) {
                if (!clock.instant().isBefore(stopStarting)) {
                    log.warn("team reports: stopped starting feeds after {}; the rest wait "
                            + "for the next run", RUN_BUDGET);
                    break;
                }
                TeamRun.FeedState held = feeds.getOrDefault(entry.getKey(),
                        TeamRun.FeedState.NEW);
                Duration interval = ReportWindows.interval(entry.getKey(), now, games);
                if (held.polled() == null || ReportWindows.due(held.polled(), interval, now)) {
                    TeamRun.FeedState read = new TeamRun(sites, cache, entry.getKey(),
                            entry.getValue(), players.get(), now, tally).run(held);
                    feeds.put(entry.getKey(), read);
                    if (read.failedRuns() >= FAILED_RUNS_LOGGED_AS_ERROR) {
                        log.error("team reports: the {} feed failed {} runs in a row",
                                entry.getValue().host(), read.failedRuns());
                    }
                }
            }
        } finally {
            store.write(STATE, new State(feeds));
        }
        Run run = tally.run();
        run.problems().forEach(problem -> log.warn("team reports: {}", problem));
        return run;
    }

    private static Comparator<Map.Entry<String, OfficialTeams.Team>> longestWaitingFirst(
            Map<String, TeamRun.FeedState> feeds) {
        Function<Map.Entry<String, OfficialTeams.Team>, TeamRun.FeedState> state =
                entry -> feeds.getOrDefault(entry.getKey(), TeamRun.FeedState.NEW);
        Comparator<Optional<Instant>> emptyLast = Comparator.comparing(
                waited -> waited.orElse(Instant.MAX));
        Comparator<Optional<Instant>> emptyFirst = Comparator.comparing(
                waited -> waited.orElse(Instant.MIN));
        return Comparator.comparing(
                        (Map.Entry<String, OfficialTeams.Team> entry) -> oldestPending(
                                state.apply(entry)), emptyLast)
                .thenComparing(entry -> Optional.ofNullable(state.apply(entry).polled()),
                        emptyFirst)
                .thenComparing(Map.Entry::getKey);
    }

    private static Optional<Instant> oldestPending(TeamRun.FeedState state) {
        return state.pending().stream()
                .map(pending -> pending.post().published())
                .min(Comparator.naturalOrder());
    }
}
