package otto.research;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import otto.directory.PlayerDirectory;
import otto.sleeper.SleeperAdapter;

/**
 * One team's share of a {@link TeamReports} run: read the feed, find
 * the new official posts, read the report they point to, and keep its
 * facts.
 */
final class TeamRun {

    static final int MAX_REPORT_READS = 3;

    private static final String FEED_PATH = "/rss/news";
    private static final String INJURY_PAGE_PATH = "/team/injury-report/";

    private static final int SEEN_PER_TEAM = 100;

    private static final Duration FUTURE_POST_TOLERANCE = Duration.ofMinutes(5);

    private final TeamSites sites;
    private final ResearchCache cache;
    private final String abbreviation;
    private final OfficialTeams.Team team;
    private final PlayerDirectory players;
    private final Instant now;
    private final Tally tally;

    TeamRun(TeamSites sites, ResearchCache cache, String abbreviation, OfficialTeams.Team team,
            PlayerDirectory players, Instant now, Tally tally) {
        this.sites = sites;
        this.cache = cache;
        this.abbreviation = abbreviation;
        this.team = team;
        this.players = players;
        this.now = now;
        this.tally = tally;
    }

    /**
     * One team's feed between runs.
     *
     * @param polled when the feed was last asked for, answered or not
     * @param bodyHash the content hash of the last body, which tells an
     *        unchanged feed from a site that sends no validators
     * @param seen the version keys of posts already handled, oldest first
     * @param pending posts whose report could not be read yet
     * @param failedRuns runs in a row whose read of the feed failed or
     *        found it in a shape Otto cannot read; 0 after one that read it
     */
    record FeedState(String etag, String lastModified, String bodyHash, Instant polled,
            List<String> seen, List<Pending> pending, int failedRuns) {

        static final FeedState NEW = new FeedState(null, null, null, null, List.of(), List.of(),
                0);

        FeedState {
            seen = List.copyOf(seen);
            pending = List.copyOf(pending);
        }

        /**
         * This state with no validators and its old body hash, so the next
         * read fetches the broken body again and reports it again.
         */
        FeedState reportedAgainNextRun(Instant now) {
            return new FeedState(null, null, bodyHash, now, seen, pending, failedRuns + 1);
        }
    }

    /** A post still waiting for its report to be read, and how many reads failed. */
    record Pending(TeamFeed.Post post, int attempts) {
    }

    /** The counts and problems of a whole run, shared by its teams. */
    static final class Tally {
        private int feedsRead;
        private int feedsUnchanged;
        private int reportsRead;
        private int itemsUpdated;
        private int readsLeft;
        private final List<String> problems = new ArrayList<>();

        Tally(int reads) {
            this.readsLeft = reads;
        }

        TeamReports.Run run() {
            return new TeamReports.Run(feedsRead, feedsUnchanged, reportsRead, itemsUpdated,
                    problems);
        }
    }

    /** Reads the feed and returns what to remember about it until the next run. */
    FeedState run(FeedState held) {
        TeamSites.Response response = sites.get(team, FEED_PATH,
                new TeamSites.Validators(held.etag(), held.lastModified()));
        FeedState polled = new FeedState(held.etag(), held.lastModified(), held.bodyHash(),
                now, held.seen(), held.pending(), 0);
        return switch (response) {
            case TeamSites.Response.Failed failed -> {
                problem("feed", failed.reason());
                yield new FeedState(held.etag(), held.lastModified(), held.bodyHash(), now,
                        held.seen(), held.pending(), held.failedRuns() + 1);
            }
            case TeamSites.Response.NotModified notModified -> {
                tally.feedsRead++;
                tally.feedsUnchanged++;
                yield handle(polled, List.of());
            }
            case TeamSites.Response.Body body -> read(held, polled, body);
        };
    }

    private FeedState read(FeedState held, FeedState polled, TeamSites.Response.Body body) {
        tally.feedsRead++;
        String hash = Fingerprint.of(body.text()).contentHash();
        FeedState answered = new FeedState(body.validators().etag(),
                body.validators().lastModified(), hash, now, polled.seen(), polled.pending(), 0);
        if (hash.equals(polled.bodyHash())) {
            tally.feedsUnchanged++;
            return handle(answered, List.of());
        }
        TeamFeed.Parsed parsed;
        try {
            parsed = TeamFeed.parse(body.text());
        } catch (LayoutChanged changed) {
            problem("feed", changed.getMessage());
            return held.reportedAgainNextRun(now);
        }
        parsed.skipped().forEach(reason -> problem("feed post", reason));
        return handle(answered, parsed.posts());
    }

    private FeedState handle(FeedState state, List<TeamFeed.Post> posts) {
        Set<String> seen = new LinkedHashSet<>(state.seen());
        Map<String, Pending> waiting = new LinkedHashMap<>();
        state.pending().forEach(pending -> waiting.put(pending.post().versionKey(), pending));
        Instant oldest = now.minus(PreSendResearch.REPORT_WINDOW);
        for (TeamFeed.Post post : posts) {
            String key = post.versionKey();
            if (seen.contains(key) || waiting.containsKey(key)
                    || post.published().isAfter(now.plus(FUTURE_POST_TOLERANCE))) {
                continue;
            }
            if (post.kind().isEmpty() || post.published().isBefore(oldest)) {
                seen.add(key);
            } else {
                waiting.put(key, new Pending(post, 0));
            }
        }
        Predicate<Pending> inactives = pending ->
                pending.post().kind().orElseThrow() == TeamFeed.Kind.INACTIVES;
        List<Pending> stillWaiting = new ArrayList<>();
        stillWaiting.addAll(readReport(waiting.values().stream()
                .filter(inactives.negate()).toList(), seen, this::injuryPage));
        stillWaiting.addAll(readReport(waiting.values().stream()
                .filter(inactives).toList(), seen, this::inactives));
        List<String> remembered = List.copyOf(seen);
        return new FeedState(state.etag(), state.lastModified(), state.bodyHash(),
                state.polled(), remembered.subList(
                        Math.max(0, remembered.size() - SEEN_PER_TEAM), remembered.size()),
                stillWaiting, state.failedRuns());
    }

    /**
     * Reads one report for the newest of the posts, which covers them
     * all, and returns the posts still waiting afterwards.
     */
    private List<Pending> readReport(List<Pending> posts, Set<String> seen, Reader reader) {
        if (posts.isEmpty()) {
            return List.of();
        }
        if (tally.readsLeft == 0) {
            return posts;
        }
        tally.readsLeft--;
        TeamFeed.Post newest = posts.stream()
                .map(Pending::post)
                .max(Comparator.comparing(TeamFeed.Post::published))
                .orElseThrow();
        if (reader.read(newest)) {
            tally.reportsRead++;
            posts.forEach(pending -> seen.add(pending.post().versionKey()));
            return List.of();
        }
        List<Pending> retry = new ArrayList<>();
        for (Pending pending : posts) {
            if (pending.attempts() + 1 < MAX_REPORT_READS) {
                retry.add(new Pending(pending.post(), pending.attempts() + 1));
            } else {
                seen.add(pending.post().versionKey());
                problem("post " + pending.post().link(),
                        "gave up after %d attempts".formatted(MAX_REPORT_READS));
            }
        }
        return retry;
    }

    @FunctionalInterface
    private interface Reader {

        /** Reads the report the post points to; false, with the problem reported, when it fails. */
        boolean read(TeamFeed.Post post);
    }

    private boolean injuryPage(TeamFeed.Post post) {
        Optional<String> html = body(INJURY_PAGE_PATH, "injury report");
        if (html.isEmpty()) {
            return false;
        }
        InjuryReportPage.Report report;
        try {
            report = InjuryReportPage.parse(html.get(), team);
        } catch (LayoutChanged changed) {
            problem("injury report", changed.getMessage());
            return false;
        }
        Optional<String> postWeek = post.week();
        if (postWeek.isPresent() && !postWeek.get().equals(report.week())) {
            problem("injury report", "page shows %s, post is for %s"
                    .formatted(report.week(), postWeek.get()));
            return false;
        }
        keep(post, report.week(), "injury-report", report.players());
        return true;
    }

    private boolean inactives(TeamFeed.Post post) {
        Optional<String> path = pathOnTeamSite(post.link());
        if (path.isEmpty()) {
            problem("inactives", "link %s is not on %s".formatted(post.link(), team.host()));
            return false;
        }
        Optional<String> week = post.week();
        if (week.isEmpty()) {
            problem("inactives", "post names no week");
            return false;
        }
        Optional<String> html = body(path.get(), "inactives");
        if (html.isEmpty()) {
            return false;
        }
        List<TeamFact> inactive;
        try {
            inactive = InactivesPost.parse(html.get(), team);
        } catch (LayoutChanged changed) {
            problem("inactives", changed.getMessage());
            return false;
        }
        keep(post, week.get(), "inactives", inactive);
        return true;
    }

    /** Returns the page's body, or empty with the problem reported. */
    private Optional<String> body(String path, String what) {
        switch (sites.get(team, path, TeamSites.Validators.NONE)) {
            case TeamSites.Response.Body body -> {
                return Optional.of(body.text());
            }
            case TeamSites.Response.NotModified notModified ->
                problem(what, "answered 304 to an unconditional read");
            case TeamSites.Response.Failed failed -> problem(what, failed.reason());
        }
        return Optional.empty();
    }

    private void keep(TeamFeed.Post post, String week, String report, List<TeamFact> facts) {
        String weekLabel = "Week " + week.substring(week.lastIndexOf('-') + 1);
        for (TeamFact fact : facts) {
            if (!fact.reportsSomething()) {
                continue;
            }
            Optional<String> playerId = ReportedPlayer.match(players, abbreviation,
                    fact.player(), fact.position());
            if (playerId.isEmpty()) {
                continue;
            }
            SleeperAdapter.NewsItem item = new SleeperAdapter.NewsItem(
                    fact.headline(team.name(), weekLabel), fact.detail(), null, team.host(),
                    storyId(report, week), post.published(), post.link());
            tally.itemsUpdated += cache.keep(playerId.get(), List.of(item)).size();
        }
    }

    /**
     * One story per report and week, so a later post that re-reads an
     * unchanged report adds nothing and a changed status replaces the
     * old one.
     */
    private static String storyId(String report, String week) {
        return report + ":" + week;
    }

    private Optional<String> pathOnTeamSite(String link) {
        String canonical = CanonicalUrl.of(link);
        if (canonical == null) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(canonical);
            return team.host().equals(uri.getHost()) && uri.getRawPath() != null
                    ? Optional.of(uri.getRawPath())
                    : Optional.empty();
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    private void problem(String what, String reason) {
        tally.problems.add("%s %s: %s".formatted(team.host(), what, reason));
    }
}
