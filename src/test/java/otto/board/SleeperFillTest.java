package otto.board;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import otto.directory.DirectoryPlayer;
import otto.directory.PlayerHealth;
import otto.nflverse.FeedId;
import otto.nflverse.PlayerUsage;
import otto.sleeper.PlayedWeekStats;

import static org.assertj.core.api.Assertions.assertThat;

class SleeperFillTest {

    private static final DirectoryPlayer DAVIS = player("10005", "BUF");
    private static final DirectoryPlayer IRVING = player("10001", "TB");

    private static final PlayedWeekStats WEEK = new PlayedWeekStats(Map.of(
            "10005", Map.of("gp", 1.0, "off_snp", 41.0, "tm_off_snp", 60.0, "rush_att", 12.0,
                    "rush_yd", 58.0, "rec", 2.0, "bonus_rec_rb", 2.0, "pts_ppr", 9.3),
            "10001", Map.of("gp", 1.0, "off_snp", 30.0, "tm_off_snp", 66.0, "rec_tgt", 1.0),
            "8138", Map.of("pos_rank_ppr", 999.0)),
            Map.of("BUF", Map.of("rec_tgt", 29.0, "rush_att", 27.0),
                    "TB", Map.of("rec_tgt", 38.0, "rush_att", 28.0)));

    private static DirectoryPlayer player(String id, String team) {
        return new DirectoryPlayer(id, "Player " + id, "RB", team, PlayerHealth.ACTIVE,
                Optional.empty());
    }

    private static SleeperFill filling(Set<String> stats, Set<String> snaps) {
        return new SleeperFill(1, Map.of(FeedId.WEEKLY_STATS, stats, FeedId.SNAP_COUNTS, snaps),
                Map.of("BUF", "MIA"), WEEK);
    }

    @Test
    void fillsOnlyTheTeamsNflverseLacks() {
        SleeperFill fill = filling(Set.of("BUF"), Set.of("BUF"));

        assertThat(fill.snaps(IRVING)).isEmpty();
        assertThat(fill.volume(IRVING)).isEmpty();
        assertThat(fill.stats(IRVING)).isEmpty();
        assertThat(fill.snaps(DAVIS)).contains(new PlayerUsage.Snaps(41, 41.0 / 60));
        assertThat(fill.opponent(DAVIS)).isEqualTo("MIA");
    }

    @Test
    void keepsOnlyTheScoredStatsAndCountsWhatAPlayedLineOmitsAsZero() {
        SleeperFill fill = filling(Set.of("BUF"), Set.of());

        assertThat(fill.stats(DAVIS).orElseThrow())
                .containsEntry("rush_att", 12.0)
                .containsEntry("rush_yd", 58.0)
                .containsEntry("rec", 2.0)
                .containsEntry("rec_tgt", 0.0)
                .doesNotContainKeys("bonus_rec_rb", "pts_ppr", "off_snp");
        assertThat(fill.volume(DAVIS)).contains(new PlayerUsage.Volume(0, 29, 0.0, 12, 27,
                12.0 / 27));
        assertThat(fill.snaps(DAVIS)).isEmpty();
    }

    @Test
    void aPlayerWithoutAPlayedLineStaysUnknown() {
        SleeperFill fill = filling(Set.of("BUF"), Set.of("BUF"));
        DirectoryPlayer cook = player("8138", "BUF");
        DirectoryPlayer absent = player("9999", "BUF");

        for (DirectoryPlayer player : new DirectoryPlayer[] {cook, absent}) {
            assertThat(fill.stats(player)).isEmpty();
            assertThat(fill.snaps(player)).isEmpty();
            assertThat(fill.volume(player)).isEmpty();
        }
    }
}
