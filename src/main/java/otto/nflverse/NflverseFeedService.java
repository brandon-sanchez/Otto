package otto.nflverse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
 */
@Component
public class NflverseFeedService {

    /** Sleeper writes the season as a four-digit year. */
    private static final Pattern SEASON = Pattern.compile("\\d{4}");

    private static final Set<String> PLAYER_ID_COLUMNS = Set.of(
            "sleeper_id", "gsis_id", "pfr_id", "position");

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

        record Downloaded(int rows) implements Update {
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
    }

    /**
     * Runs the timestamp checks whose interval has elapsed. One broken
     * feed self-reports and leaves the others to finish their work.
     */
    public Result updateIfDue() {
        Instant now = clock.instant();
        Map<FeedId, Update> feeds = new LinkedHashMap<>();
        Feeds.ALL.forEach(spec -> feeds.put(spec.id(), report(update(spec, now))));
        return new Result(feeds, report(updatePlayerIds(now)));
    }

    private <R, D extends NflverseFeed<R>> Update update(FeedSpec<R, D> spec, Instant now) {
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
            case Decision.Touch ignored -> {
                D current = stored.orElseThrow();
                store.write(spec, spec.document(basis, current.assetUpdatedAt(), now,
                        current.rows()));
                yield new Update.Unchanged();
            }
            case Decision.Download download -> switch (client.downloadAsset(spec.repo(),
                    spec.tag(), asset, rows -> spec.read(basis, rows))) {
                case SourceResult.Unavailable<List<R>> unavailable ->
                    new Update.Unavailable(unavailable.source(), unavailable.reason());
                case SourceResult.Ok<List<R>> ok -> {
                    store.write(spec, spec.document(basis, download.assetUpdatedAt(), now,
                            ok.value()));
                    yield new Update.Downloaded(ok.value().size());
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

    private boolean due(Optional<? extends NflverseFeed<?>> stored, Instant now) {
        return stored
                .map(feed -> Duration.between(feed.checkedAt(), now).compareTo(checkInterval) >= 0)
                .orElse(true);
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
     */
    private Decision decide(FeedSpec<?, ?> spec, String asset,
            Optional<? extends NflverseFeed<?>> stored, String season) {
        SourceResult<Instant> published = publishedAt(spec, asset);
        if (published instanceof SourceResult.Unavailable<Instant> unavailable) {
            return new Decision.Blocked(unavailable.source(), unavailable.reason());
        }
        Instant assetUpdatedAt = ((SourceResult.Ok<Instant>) published).value();
        boolean stillCurrent = stored
                .filter(feed -> feed.season().equals(season))
                .filter(feed -> feed.assetUpdatedAt().equals(assetUpdatedAt))
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
     * reads the season Sleeper publishes.
     *
     * A last-played feed reads last season's final record in week 1,
     * which has no played week of its own, and the current season to
     * date from week 2 on. The preseason reads the same way as week 1,
     * and it has to be asked about separately: Sleeper counts preseason
     * weeks from 1, so August reads as week 2 or later while no game
     * that counts has been played. nflverse publishes a season's weekly
     * file once there are rows to put in it, so reading the week alone
     * asks for a file that does not exist and blinds the table for a
     * month.
     */
    private static Optional<FeedSpec.Basis> basisFor(SleeperAdapter.NflState state,
            FeedSpec.SeasonRule rule) {
        if (rule == FeedSpec.SeasonRule.CURRENT
                || (state.week() > 1 && !state.beforeTheSeason())) {
            return Optional.of(new FeedSpec.Basis(state.season(), false));
        }
        // Sleeper writes the season as text, so a drifted value must not
        // throw out of a job that still has other feeds to update.
        if (!SEASON.matcher(state.season()).matches()) {
            return Optional.empty();
        }
        return Optional.of(new FeedSpec.Basis(
                String.valueOf(Integer.parseInt(state.season()) - 1), true));
    }

    // -- player id mapping --------------------------------------------------

    private Update updatePlayerIds(Instant now) {
        Optional<PlayerIdMap> stored = store.playerIds();
        if (stored.isPresent() && Duration.between(stored.get().checkedAt(), now)
                .compareTo(checkInterval) < 0) {
            return new Update.Skipped();
        }

        SourceResult<NflverseClient.Downloaded<PlayerMappings>> result =
                client.downloadPlayerIds(stored.map(PlayerIdMap::etag).orElse(null),
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
                        mappings.sleeperToGsis(), mappings.sleeperToPfr()));
                yield new Update.Downloaded(mappings.retainedPlayers());
            }
        };
    }

    private record PlayerMappings(Map<String, String> sleeperToGsis,
            Map<String, String> sleeperToPfr, int retainedPlayers) { }

    private static PlayerMappings playerMappings(Stream<Csv.Row> rows) {
        Map<String, String> gsis = new HashMap<>();
        Map<String, String> pfr = new HashMap<>();
        Set<String> retained = new HashSet<>();
        rows.forEach(row -> {
            requireColumns(row, PLAYER_ID_COLUMNS);
            String sleeperId = row.text("sleeper_id");
            String gsisId = row.text("gsis_id");
            String pfrId = row.text("pfr_id");
            if (blankOrNa(sleeperId) || !KEPT_POSITIONS.contains(row.text("position"))) {
                return;
            }
            retained.add(sleeperId);
            if (!blankOrNa(gsisId)) {
                gsis.put(sleeperId, gsisId);
            }
            if (!blankOrNa(pfrId)) {
                pfr.put(sleeperId, pfrId);
            }
        });
        return new PlayerMappings(Map.copyOf(gsis), Map.copyOf(pfr), retained.size());
    }
}
