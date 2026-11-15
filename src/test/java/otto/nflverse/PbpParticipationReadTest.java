package otto.nflverse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PbpParticipationReadTest {

    private static final FeedSpec.Basis BASIS = new FeedSpec.Basis("2025", true);

    private static final String HEADER = "nflverse_game_id,possession_team,offense_players,"
            + "offense_positions,was_pressure,defense_man_zone_type,defense_coverage_type\n";

    private static Stream<Csv.Row> csv(String body) {
        return Csv.stream(new ByteArrayInputStream(
                (HEADER + body).getBytes(StandardCharsets.UTF_8)));
    }

    private static String row(String players, String positions, String pressure) {
        return "2025_01_KC_LAC,KC,%s,%s,%s,MAN_COVERAGE,COVER_1\n"
                .formatted(players, positions, pressure);
    }

    @Test
    void aRowWhosePlayersAndPositionsDisagreeIsSkippedNotFatal() {
        var games = Feeds.PBP_PARTICIPATION.read(BASIS, csv(
                row("00-1;00-2", "WR;TE", "FALSE")
                        + row("00-1;00-2;00-3", "WR;TE", "FALSE")));

        assertThat(games).singleElement().satisfies(game -> {
            assertThat(game.dropbacks()).isEqualTo(1);
            assertThat(game.onField()).containsEntry("00-1", 1).containsEntry("00-2", 1);
        });
    }

    @Test
    void aPressureIsReadInEverySpellingOfTrue() {
        var games = Feeds.PBP_PARTICIPATION.read(BASIS, csv(
                row("00-1", "WR", "TRUE") + row("00-1", "WR", "true") + row("00-1", "WR", "1")
                        + row("00-1", "WR", "FALSE") + row("00-1", "WR", "0")
                        + row("00-1", "WR", "NA")));

        assertThat(games).singleElement()
                .satisfies(game -> assertThat(game.pressures()).isEqualTo(3));
    }

    @Test
    void anUnknownPressureSpellingFailsTheDownload() {
        assertThatThrownBy(() -> Feeds.PBP_PARTICIPATION.read(BASIS,
                csv(row("00-1", "WR", "maybe"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("was_pressure");
    }
}
