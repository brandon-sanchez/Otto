package otto.nflverse;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import otto.storage.JsonStore;

/** The stored nflverse documents and the table built from them. */
@Component
public class NflverseStore {

    private static final String PLAYER_IDS = "nflverse-player-ids";
    private static final String DEFENSE_VERSUS_POSITION = "defense-versus-position";

    private final JsonStore store;

    public NflverseStore(JsonStore store) {
        this.store = store;
    }

    /**
     * Names the feeds as one evidence source, such as
     * "nflverse:ftn_charting+pbp": their release tags in order, each once.
     */
    public static String source(List<FeedId> feeds) {
        return "nflverse:" + feeds.stream()
                .map(feed -> Feeds.of(feed).tag())
                .distinct()
                .collect(Collectors.joining("+"));
    }

    /** Returns when the newest stored copy of the feeds was published; empty when none is stored. */
    public Optional<Instant> newestPublished(List<FeedId> feeds) {
        return feeds.stream()
                .flatMap(feed -> read(Feeds.of(feed)).stream())
                .map(NflverseFeed::assetUpdatedAt)
                .max(Instant::compareTo);
    }

    <R, D extends NflverseFeed> Optional<D> read(FeedSpec<R, D> spec) {
        return store.read(spec.documentName(), spec.type());
    }

    <R, D extends NflverseFeed> void write(FeedSpec<R, D> spec, D document) {
        store.write(spec.documentName(), document);
    }

    /** Returns the stored season schedule, or empty before its first download. */
    public Optional<Schedule> schedule() {
        return read(Feeds.SCHEDULE);
    }

    /** Returns the stored weekly player stats, or empty before their first download. */
    public Optional<WeeklyStats> weeklyStats() {
        return read(Feeds.WEEKLY_STATS);
    }

    /** Returns the newest stored depth chart per team, or empty before its first download. */
    public Optional<DepthCharts> depthCharts() {
        return read(Feeds.DEPTH_CHARTS);
    }

    /** Returns the stored weekly rosters, or empty before their first download. */
    public Optional<WeeklyRosters> weeklyRosters() {
        return read(Feeds.WEEKLY_ROSTERS);
    }

    /** Returns the stored snap counts, or empty before their first download. */
    public Optional<SnapCounts> snapCounts() {
        return read(Feeds.SNAP_COUNTS);
    }

    /** Returns FTN's stored charting of this season, or empty before its first download. */
    public Optional<FtnCharting> ftnCharting() {
        return read(Feeds.FTN_CHARTING);
    }

    /** Returns the stored PFR advanced defense file, or empty before its first download. */
    public Optional<PfrDefense> pfrDefense() {
        return read(Feeds.PFR_DEFENSE);
    }

    /** Returns the stored PFR advanced passing file, or empty before its first download. */
    public Optional<PfrPassing> pfrPassing() {
        return read(Feeds.PFR_PASSING);
    }

    /** Returns the stored PFR advanced rushing file, or empty before its first download. */
    public Optional<RushingAdvancedStats> rushingAdvancedStats() {
        return read(Feeds.RUSHING_ADVANCED_STATS);
    }

    /** Returns last season's stored participation file, or empty before its first download. */
    public Optional<PbpParticipation> pbpParticipation() {
        return read(Feeds.PBP_PARTICIPATION);
    }

    /** The stored leaderboard of one kind, or empty before its first download. */
    public Optional<NextGenStats> nextGenStats(NextGenStats.Kind kind) {
        return read(switch (kind) {
            case PASSING -> Feeds.NGS_PASSING;
            case RECEIVING -> Feeds.NGS_RECEIVING;
            case RUSHING -> Feeds.NGS_RUSHING;
        });
    }

    /** Returns the stored play-by-play, or empty before its first download. */
    public Optional<PlayByPlay> playByPlay() {
        return read(Feeds.PLAY_BY_PLAY);
    }

    /** Returns the stored expected fantasy points, or empty before their first download. */
    public Optional<ExpectedPoints> expectedPoints() {
        return read(Feeds.EXPECTED_POINTS);
    }

    /** Returns the stored nflverse player register, or empty before its first download. */
    public Optional<Players> players() {
        return read(Feeds.PLAYERS);
    }

    /** Returns the stored Player-ID Map, or empty before it is first built. */
    public Optional<PlayerIdMap> playerIds() {
        return store.read(PLAYER_IDS, PlayerIdMap.class);
    }

    public void writePlayerIds(PlayerIdMap idMap) {
        store.write(PLAYER_IDS, idMap);
    }

    /** Returns the stored defense-versus-position table, or empty before it is first built. */
    public Optional<DefenseVersusPosition> defenseVersusPosition() {
        return store.read(DEFENSE_VERSUS_POSITION, DefenseVersusPosition.class);
    }

    public void writeDefenseVersusPosition(DefenseVersusPosition table) {
        store.write(DEFENSE_VERSUS_POSITION, table);
    }
}
