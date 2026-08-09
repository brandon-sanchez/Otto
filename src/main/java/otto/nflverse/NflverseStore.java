package otto.nflverse;

import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.storage.JsonStore;

/** The stored nflverse documents and the table built from them. */
@Component
public class NflverseStore {

    private static final String WEEKLY_STATS = "nflverse-weekly-stats";
    private static final String DEPTH_CHARTS = "nflverse-depth-charts";
    private static final String WEEKLY_ROSTERS = "nflverse-weekly-rosters";
    private static final String PLAYER_IDS = "nflverse-player-ids";
    private static final String DEFENSE_VERSUS_POSITION = "defense-versus-position";

    private final JsonStore store;

    public NflverseStore(JsonStore store) {
        this.store = store;
    }

    public Optional<WeeklyStats> weeklyStats() {
        return store.read(WEEKLY_STATS, WeeklyStats.class);
    }

    public void writeWeeklyStats(WeeklyStats stats) {
        store.write(WEEKLY_STATS, stats);
    }

    public Optional<DepthCharts> depthCharts() {
        return store.read(DEPTH_CHARTS, DepthCharts.class);
    }

    public void writeDepthCharts(DepthCharts charts) {
        store.write(DEPTH_CHARTS, charts);
    }

    public Optional<WeeklyRosters> weeklyRosters() {
        return store.read(WEEKLY_ROSTERS, WeeklyRosters.class);
    }

    public void writeWeeklyRosters(WeeklyRosters rosters) {
        store.write(WEEKLY_ROSTERS, rosters);
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
