package otto.board;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import otto.directory.DirectoryPlayer;
import otto.directory.PlayerHealth;
import otto.nflverse.PlayerUsage;

import static org.assertj.core.api.Assertions.assertThat;

class SleeperFillTest {

    private static final DirectoryPlayer DAVIS = player("10005", "BUF");
    private static final DirectoryPlayer IRVING = player("10001", "TB");

    private static final Map<String, Map<String, Double>> LINES = Map.of(
            "10005", Map.of("off_snp", 41.0, "tm_off_snp", 60.0, "rush_att", 12.0,
                    "rush_yd", 58.0, "rec", 2.0, "bonus_rec_rb", 2.0, "pts_ppr", 9.3),
            "10001", Map.of("off_snp", 30.0, "tm_off_snp", 66.0, "rec_tgt", 1.0),
            "TEAM_BUF", Map.of("rec_tgt", 29.0, "rush_att", 27.0),
            "TEAM_TB", Map.of("rec_tgt", 38.0, "rush_att", 28.0));

    private static DirectoryPlayer player(String id, String team) {
        return new DirectoryPlayer(id, "Player " + id, "RB", team, PlayerHealth.ACTIVE,
                Optional.empty());
    }

    @Test
    void fillsOnlyTheTeamsNflverseLacks() {
        SleeperFill fill = new SleeperFill(1, Set.of("BUF"), Set.of("BUF"),
                Map.of("BUF", "MIA"), LINES);

        assertThat(fill.snaps(IRVING)).isEmpty();
        assertThat(fill.volume(IRVING)).isEmpty();
        assertThat(fill.stats(IRVING)).isEmpty();
        assertThat(fill.snaps(DAVIS)).contains(new PlayerUsage.Snaps(41, 41.0 / 60));
        assertThat(fill.opponent(DAVIS)).isEqualTo("MIA");
    }

    @Test
    void keepsOnlyTheStatsBothSourcesScoreAndCountsAnOmittedTargetAsNone() {
        SleeperFill fill = new SleeperFill(1, Set.of("BUF"), Set.of(), Map.of(), LINES);

        assertThat(fill.stats(DAVIS)).contains(Map.of("rush_att", 12.0, "rush_yd", 58.0,
                "rec", 2.0));
        assertThat(fill.volume(DAVIS)).contains(new PlayerUsage.Volume(0, 29, 0.0, 12, 27,
                12.0 / 27));
        assertThat(fill.snaps(DAVIS)).isEmpty();
    }

    @Test
    void aPlayerWithoutASleeperLineStaysUnknown() {
        SleeperFill fill = new SleeperFill(1, Set.of("BUF"), Set.of("BUF"), Map.of(), LINES);
        DirectoryPlayer cook = player("8138", "BUF");

        assertThat(fill.stats(cook)).isEmpty();
        assertThat(fill.snaps(cook)).isEmpty();
        assertThat(fill.volume(cook)).isEmpty();
    }
}
