package otto.nflverse;

import java.util.Optional;

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

    <R, D extends NflverseFeed<R>> Optional<D> read(FeedSpec<R, D> spec) {
        return store.read(spec.documentName(), spec.type());
    }

    <R, D extends NflverseFeed<R>> void write(FeedSpec<R, D> spec, D document) {
        store.write(spec.documentName(), document);
    }

    public Optional<Schedule> schedule() {
        return read(Feeds.SCHEDULE);
    }

    public Optional<WeeklyStats> weeklyStats() {
        return read(Feeds.WEEKLY_STATS);
    }

    public Optional<DepthCharts> depthCharts() {
        return read(Feeds.DEPTH_CHARTS);
    }

    public Optional<WeeklyRosters> weeklyRosters() {
        return read(Feeds.WEEKLY_ROSTERS);
    }

    public Optional<SnapCounts> snapCounts() {
        return read(Feeds.SNAP_COUNTS);
    }

    public Optional<PlayerIdMap> playerIds() {
        return store.read(PLAYER_IDS, PlayerIdMap.class);
    }

    public void writePlayerIds(PlayerIdMap idMap) {
        store.write(PLAYER_IDS, idMap);
    }

    public Optional<DefenseVersusPosition> defenseVersusPosition() {
        return store.read(DEFENSE_VERSUS_POSITION, DefenseVersusPosition.class);
    }

    public void writeDefenseVersusPosition(DefenseVersusPosition table) {
        store.write(DEFENSE_VERSUS_POSITION, table);
    }
}
