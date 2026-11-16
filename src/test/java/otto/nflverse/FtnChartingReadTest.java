package otto.nflverse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FtnChartingReadTest {

    @Test
    void aFractionalBlitzerCountFailsTheDownloadAsDrift() {
        String csv = "nflverse_game_id,week,nflverse_play_id,date_pulled,read_thrown,"
                + "n_blitzers,n_pass_rushers\n"
                + "2026_04_LAC_SEA,4,20,2026-10-01T12:00:00Z,1,1.5,5\n";

        assertThatThrownBy(() -> Feeds.FTN_CHARTING.read(new FeedSpec.Basis("2026", false),
                Csv.stream(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema drift")
                .hasMessageContaining("n_blitzers");
    }

    @Test
    void aWholeBlitzerCountWrittenWithADecimalPointIsRead() {
        String csv = "nflverse_game_id,week,nflverse_play_id,date_pulled,read_thrown,"
                + "n_blitzers,n_pass_rushers\n"
                + "2026_04_LAC_SEA,4,20,2026-10-01T12:00:00Z,1,2.0,5\n";

        assertThat(Feeds.FTN_CHARTING.read(new FeedSpec.Basis("2026", false),
                Csv.stream(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)))))
                .singleElement()
                .satisfies(play -> assertThat(play.blitzers()).isEqualTo(2));
    }
}
