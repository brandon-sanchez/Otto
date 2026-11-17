package otto.nflverse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.alerts.SelfReportService;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;

import static otto.nflverse.FeedRows.KEPT_POSITIONS;
import static otto.nflverse.FeedRows.blankOrNa;
import static otto.nflverse.FeedRows.requireColumns;

/**
 * Keeps the nflverse feeds current: an hourly check of the release
 * index, and a download only when an asset's publish timestamp has
 * moved. Shaped like {@link otto.directory.PlayerDirectoryService} -
 * one update method, gated by its own interval, returning what each
 * source did. The season files run to tens of megabytes and are republished
 * at most once a day, so the timestamp is what makes an hourly cadence
 * affordable.
 *
 * Every release-asset feed runs through the same flow, driven by its
 * {@link FeedSpec} in {@link Feeds#ALL}. The player-id map has no
 * release index, so it keeps its own ETag flow.
 *
 * Each run logs its {@link Result#summary()} at INFO, so the logs show
 * when each feed changed and when each week arrived in full.
 */
@Component
public class NflverseFeedService {

    private static final Logger log = LoggerFactory.getLogger(NflverseFeedService.class);

    /**
     * How much earlier than a full interval a run may fire and still be
     * due. The schedule fires once an hour, give or take a little, and
     * a run that lands a moment short of a full interval since the last
     * check must not wait a whole extra interval.
     */
    static final Duration SCHEDULE_JITTER = Duration.ofMinutes(1);

    private static final Set<String> PLAYER_ID_COLUMNS = Set.of(
            "sleeper_id", "gsis_id", "pfr_id", "position");

    /** The mapping's own letters for a defender. */
    private static final Set<String> DEFENSIVE_ID_POSITIONS = Set.of(
            "DL", "DE", "DT", "LB", "DB", "CB", "S");

    private final NflverseClient client;
    private final NflverseStore store;
    private final SleeperAdapter sleeper;
    private final SelfReportService selfReport;
    private final Clock clock;
    private final Duration checkInterval;

    public NflverseFeedService(NflverseClient client, NflverseStore store, SleeperAdapter sleeper,
            SelfReportService selfReport, Clock clock, OttoProperties properties) {
        this.client = client;
        this.store = store;
        // This job is not the Check, so it has no business polling: the
        // week it needs is whatever the Check last saw.
        this.sleeper = sleeper.cachedWithin(properties.sleeperCadence());
        this.selfReport = selfReport;
        this.clock = clock;
        this.checkInterval = properties.nflverse().checkInterval();
    }

    /** What one source did on this run. */
    public sealed interface Update {

        record Skipped() implements Update {
        }

        record Unchanged() implements Update {
        }

        /**
         * @param rows the rows kept from the file
         * @param weeksCompleted the weeks of the stored season the feed
         *        holds in full after this download and did not before,
         *        oldest first
         */
        record Downloaded(int rows, List<Integer> weeksCompleted) implements Update {

            public Downloaded {
                weeksCompleted = List.copyOf(weeksCompleted);
            }
        }

        record Unavailable(String source, String reason) implements Update {
        }
    }

    /** Each feed's outcome in registry order, then the player-id map's. */
    public record Result(Map<FeedId, Update> feeds, Update playerIds) {

        public Result {
            feeds = Collections.unmodifiableMap(new LinkedHashMap<>(feeds));
        }

        public Update feed(FeedId id) {
            Update update = feeds.get(id);
            if (update == null) {
                throw new IllegalArgumentException(id + " is not in Feeds.ALL");
            }
            return update;
        }

        /**
         * Returns one clause per source, such as "snap counts downloaded
         * 120 rows, week 1 now complete", joined by semicolons.
         */
        public String summary() {
            List<String> clauses = new ArrayList<>();
            feeds.forEach((id, update) -> clauses.add(
                    id.name().toLowerCase(Locale.ROOT).replace('_', ' ') + " " + describe(update)));
            clauses.add("player ids " + describe(playerIds));
            return String.join("; ", clauses);
        }

        private static String describe(Update update) {
            return switch (update) {
                case Update.Skipped _ -> "not due";
                case Update.Unchanged _ -> "unchanged";
                case Update.Unavailable unavailable -> "unavailable from %s: %s"
                        .formatted(unavailable.source(), unavailable.reason());
                case Update.Downloaded downloaded -> "downloaded %d rows%s".formatted(
                        downloaded.rows(), downloaded.weeksCompleted().stream()
                                .map(week -> ", week " + week + " now complete")
                                .collect(Collectors.joining()));
            };
        }
    }

    /**
     * Runs the timestamp checks whose interval has elapsed. One broken
     * feed self-reports and leaves the others to finish their work.
     */
    public Result updateIfDue() {
        Instant now = clock.instant();
        Map<FeedId, Update> feeds = new LinkedHashMap<>();
        Feeds.ALL.forEach(spec -> feeds.put(spec.id(), report(update(spec, now))));
        Result result = new Result(feeds, report(updatePlayerIds(now)));
        log.info("nflverse run: {}", result.summary());
        return result;
    }

    private <R, D extends NflverseFeed> Update update(FeedSpec<R, D> spec, Instant now) {
        Optional<D> stored = store.read(spec);
        if (!due(stored, now)) {
            return new Update.Skipped();
        }

        SourceResult<SleeperAdapter.NflState> state = sleeper.nflState();
        if (state instanceof SourceResult.Unavailable<SleeperAdapter.NflState> unavailable) {
            return new Update.Unavailable(unavailable.source(), unavailable.reason());
        }
        SleeperAdapter.NflState nflWeek = ((SourceResult.Ok<SleeperAdapter.NflState>) state).value();
        Optional<FeedSpec.Basis> resolved = basisFor(nflWeek, spec.seasonRule());
        if (resolved.isEmpty()) {
            return new Update.Unavailable("sleeper:/v1/state/nfl",
                    "season \"%s\" is not a year, so I cannot name the %s file"
                            .formatted(nflWeek.season(), spec.tag()));
        }
        FeedSpec.Basis basis = resolved.get();
        String asset = spec.asset(basis.season());

        return switch (decide(spec, asset, stored, basis.season())) {
            case Decision.Blocked blocked ->
                new Update.Unavailable(blocked.source(), blocked.reason());
            case Decision.Touch _ -> {
                store.write(spec, spec.recheck(stored.orElseThrow(), basis, now));
                yield new Update.Unchanged();
            }
            case Decision.Download download -> switch (client.downloadAsset(spec.repo(),
                    spec.tag(), asset, rows -> spec.read(basis, rows))) {
                case SourceResult.Unavailable<List<R>> unavailable ->
                    new Update.Unavailable(unavailable.source(), unavailable.reason());
                case SourceResult.Ok<List<R>> ok -> {
                    Instant published = download.assetUpdatedAt();
                    // Last season's units share week numbers and team codes
                    // with this season's, so only the same season's history
                    // is carried over.
                    Coverage previous = stored
                            .filter(feed -> feed.season().equals(basis.season()))
                            .map(NflverseFeed::coverage)
                            .orElse(null);
                    Coverage coverage = Coverage.of(ok.value(), spec, published)
                            .carriedForward(previous, published);
                    store.write(spec, spec.document(basis, published, now, ok.value(), coverage));
                    yield new Update.Downloaded(ok.value().size(),
                            weeksCompleted(spec, basis.season(), previous, coverage, now));
                }
            };
        };
    }

    private Update report(Update update) {
        if (update instanceof Update.Unavailable unavailable) {
            selfReport.report(unavailable.source(), unavailable.reason());
        }
        return update;
    }

    private boolean due(Optional<? extends NflverseFeed> stored, Instant now) {
        return stored.map(feed -> due(feed.checkedAt(), now)).orElse(true);
    }

    private boolean due(Instant checkedAt, Instant now) {
        return Duration.between(checkedAt, now)
                .compareTo(checkInterval.minus(SCHEDULE_JITTER)) > 0;
    }

    /**
     * Returns the weeks up to the schedule's latest completed one that
     * the new coverage holds in full and the previous one did not.
     * Empty for the schedule itself, which every week is measured
     * against, for a feed with no weeks, and without a schedule.
     */
    private List<Integer> weeksCompleted(FeedSpec<?, ?> spec, String season,
            Coverage previous, Coverage current, Instant now) {
        if (spec.id() == FeedId.SCHEDULE || spec.grain() instanceof Grain.Whole<?>) {
            return List.of();
        }
        Optional<Schedule> schedule = store.schedule();
        OptionalInt latest = schedule
                .map(held -> held.latestCompletedWeek(season))
                .orElse(OptionalInt.empty());
        if (latest.isEmpty()) {
            return List.of();
        }
        return IntStream.rangeClosed(1, latest.getAsInt())
                .filter(week -> complete(schedule.get(), spec, current, season, week, now))
                .filter(week -> previous == null
                        || !complete(schedule.get(), spec, previous, season, week, now))
                .boxed()
                .toList();
    }

    private static boolean complete(Schedule schedule, FeedSpec<?, ?> spec, Coverage coverage,
            String season, int week, Instant now) {
        return NflverseReadiness.status(schedule, spec.grain(), spec.due(), coverage, season,
                week, now) instanceof WeekStatus.Complete;
    }

    /** What the timestamp check says to do about one release asset. */
    private sealed interface Decision {

        /** The stored copy is still the published one; move its clock. */
        record Touch() implements Decision {
        }

        record Download(Instant assetUpdatedAt) implements Decision {
        }

        record Blocked(String source, String reason) implements Decision {
        }
    }

    /**
     * The timestamp check, defined once for every release asset: read
     * the release index, and download only when the stored copy did not
     * come from the same season's file at the same publish time - in
     * which case the bytes on the wire are the bytes already on disk and
     * only the checked-at time needs moving.
     *
     * A copy whose coverage is missing, or was fingerprinted under another
     * content version, is not current however fresh its timestamp: it was
     * stored before this code measured it that way. Downloading it once
     * more brings it level against the same file, so nothing that changed
     * in the meantime is mistaken for, or hidden by, the new measure.
     */
    private Decision decide(FeedSpec<?, ?> spec, String asset,
            Optional<? extends NflverseFeed> stored, String season) {
        SourceResult<Instant> published = publishedAt(spec, asset);
        if (published instanceof SourceResult.Unavailable<Instant> unavailable) {
            return new Decision.Blocked(unavailable.source(), unavailable.reason());
        }
        Instant assetUpdatedAt = ((SourceResult.Ok<Instant>) published).value();
        boolean stillCurrent = stored
                .filter(feed -> feed.season().equals(season))
                .filter(feed -> feed.assetUpdatedAt().equals(assetUpdatedAt))
                .filter(feed -> feed.coverage() != null
                        && feed.coverage().contentVersion() == spec.contentVersion())
                .isPresent();
        return stillCurrent ? new Decision.Touch() : new Decision.Download(assetUpdatedAt);
    }

    private SourceResult<Instant> publishedAt(FeedSpec<?, ?> spec, String asset) {
        return client.assetTimestamps(spec.repo(), spec.tag()).flatMap(timestamps -> {
            Instant published = timestamps.get(asset);
            if (published == null) {
                return new SourceResult.Unavailable<>("nflverse:" + spec.tag(),
                        "the release carries no asset named " + asset);
            }
            return new SourceResult.Ok<>(published);
        });
    }

    /**
     * Which season's file a feed names this week. A current-season feed
     * reads the season Sleeper publishes, and a previous-season feed the
     * one before it.
     *
     * A last-played feed reads last season's final record until a game
     * of this season is final, and the current season to date from
     * then on. nflverse publishes a season's weekly file once there are
     * rows to put in it, so asking for it earlier asks for a file that
     * does not exist and blinds the table for a month. The stored
     * schedule says whether a game is final; it runs first, so a run
     * that brings in the first result reads the new file in the same
     * hour. Only without a schedule does Sleeper's counter stand in:
     * week 2 or later and not the preseason, which Sleeper also counts
     * in weeks from 1.
     *
     * Sleeper writes the season as text, so a drifted value answers
     * empty rather than throwing out of a job with other feeds to run.
     */
    private Optional<FeedSpec.Basis> basisFor(SleeperAdapter.NflState state,
            FeedSpec.SeasonRule rule) {
        if (rule == FeedSpec.SeasonRule.CURRENT) {
            return Optional.of(new FeedSpec.Basis(state.season(), false));
        }
        if (rule == FeedSpec.SeasonRule.PREVIOUS) {
            return Seasons.previous(state.season())
                    .map(previous -> new FeedSpec.Basis(previous, true));
        }
        boolean played = store.schedule()
                .map(schedule -> schedule.anyFinal(state.season()))
                .orElseGet(() -> state.week() > 1 && !state.beforeTheSeason());
        if (played) {
            return Optional.of(new FeedSpec.Basis(state.season(), false));
        }
        return Seasons.previous(state.season())
                .map(previous -> new FeedSpec.Basis(previous, true));
    }

    // -- player id mapping --------------------------------------------------

    private Update updatePlayerIds(Instant now) {
        Optional<PlayerIdMap> stored = store.playerIds();
        if (stored.isPresent() && !due(stored.get().checkedAt(), now)) {
            return new Update.Skipped();
        }

        SourceResult<NflverseClient.Downloaded<PlayerMappings>> result =
                client.downloadPlayerIds(stored.filter(PlayerIdMap::hasFantasyProsIds)
                                .map(PlayerIdMap::etag).orElse(null),
                        NflverseFeedService::playerMappings);
        return switch (result) {
            case SourceResult.Unavailable<NflverseClient.Downloaded<PlayerMappings>> unavailable ->
                new Update.Unavailable(unavailable.source(), unavailable.reason());
            case SourceResult.Ok<NflverseClient.Downloaded<PlayerMappings>> ok -> {
                if (ok.value().notModified()) {
                    if (stored.isEmpty()) {
                        yield new Update.Unavailable("nflverse:player-ids",
                                "304 without a stored mapping");
                    }
                    store.writePlayerIds(stored.get().withCheckedAt(now));
                    yield new Update.Unchanged();
                }
                PlayerMappings mappings = ok.value().value();
                store.writePlayerIds(new PlayerIdMap(ok.value().etag(), now,
                        mappings.sleeperToGsis(), mappings.sleeperToPfr(),
                        mappings.sleeperToFantasyPros()));
                yield new Update.Downloaded(mappings.retainedPlayers(), List.of());
            }
        };
    }

    private record PlayerMappings(Map<String, String> sleeperToGsis,
            Map<String, String> sleeperToPfr, Map<String, String> sleeperToFantasyPros,
            int retainedPlayers) { }

    private static PlayerMappings playerMappings(Stream<Csv.Row> rows) {
        Map<String, String> gsis = new HashMap<>();
        Map<String, String> pfr = new HashMap<>();
        Map<String, String> fantasyPros = new HashMap<>();
        Set<String> retained = new HashSet<>();
        rows.forEach(row -> {
            requireColumns(row, PLAYER_ID_COLUMNS);
            String sleeperId = row.text("sleeper_id");
            String gsisId = row.text("gsis_id");
            String pfrId = row.text("pfr_id");
            String fantasyProsId = row.text("fantasypros_id");
            String position = row.text("position");
            if (blankOrNa(sleeperId) || !(KEPT_POSITIONS.contains(position)
                    || DEFENSIVE_ID_POSITIONS.contains(position))) {
                return;
            }
            retained.add(sleeperId);
            if (!blankOrNa(gsisId)) {
                gsis.put(sleeperId, gsisId);
            }
            if (!blankOrNa(pfrId)) {
                pfr.put(sleeperId, pfrId);
            }
            if (!blankOrNa(fantasyProsId)) {
                fantasyPros.put(sleeperId, fantasyProsId);
            }
        });
        return new PlayerMappings(Map.copyOf(gsis), Map.copyOf(pfr), Map.copyOf(fantasyPros),
                retained.size());
    }
}
