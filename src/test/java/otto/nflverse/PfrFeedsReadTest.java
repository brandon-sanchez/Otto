package otto.nflverse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PfrFeedsReadTest {

    private static final FeedSpec.Basis BASIS = new FeedSpec.Basis("2025", false);

    private static final String PASS_HEADER = "game_id,week,game_type,team,opponent,"
            + "pfr_player_name,pfr_player_id,times_pressured\n";

    private static Stream<Csv.Row> csv(String body) {
        return Csv.stream(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void playoffRowsAreDroppedAndTeamsSpeakSleepersVocabulary() {
        var lines = Feeds.PFR_PASSING.read(BASIS, csv(PASS_HEADER
                + "2025_01_DAL_PHI,1,REG,LA,SF,Matthew Stafford,StafMa00,7\n"
                + "2025_19_GB_CHI,19,WC,GB,CHI,Jordan Love,LoveJo03,9\n"));

        assertThat(lines).singleElement().satisfies(line -> {
            assertThat(line.team()).isEqualTo("LAR");
            assertThat(line.timesPressured()).isEqualTo(7);
        });
    }

    @Test
    void eachDefensesPlayersAreSummedIntoOneRowPerGame() {
        var games = Feeds.PFR_DEFENSE.read(BASIS, csv(
                "game_id,week,game_type,team,opponent,pfr_player_id,def_pressures\n"
                        + "2025_01_DAL_PHI,1,REG,PHI,DAL,BaunZa00,3\n"
                        + "2025_01_DAL_PHI,1,REG,PHI,DAL,CartJa00,2\n"
                        + "2025_01_DAL_PHI,1,REG,DAL,PHI,ParsMi00,4\n"));

        assertThat(games).containsExactly(
                new PfrDefense.GamePressures("2025_01_DAL_PHI", 1, "PHI", "DAL", 5),
                new PfrDefense.GamePressures("2025_01_DAL_PHI", 1, "DAL", "PHI", 4));
    }

    @Test
    void aRenamedPressureColumnFailsTheDownload() {
        assertThatThrownBy(() -> Feeds.PFR_DEFENSE.read(BASIS, csv(
                "game_id,week,game_type,team,opponent,def_prss\n"
                        + "2025_01_DAL_PHI,1,REG,PHI,DAL,3\n")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("def_pressures");
    }
}
