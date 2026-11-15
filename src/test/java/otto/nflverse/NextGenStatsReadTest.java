package otto.nflverse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NextGenStatsReadTest {

    private static final FeedSpec.Basis BASIS = new FeedSpec.Basis("2026", false);

    private static final String HEADER = "season,season_type,week,player_gsis_id,"
            + "player_display_name,player_position,team_abbr,attempts,avg_time_to_throw,"
            + "aggressiveness,avg_intended_air_yards,avg_air_yards_to_sticks,"
            + "completion_percentage_above_expectation\n";

    private static Stream<Csv.Row> csv(String body) {
        return Csv.stream(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aBlankOrNaMetricIsUnknownRatherThanZero() {
        var lines = Feeds.NGS_PASSING.read(BASIS, csv(HEADER
                + "2026,REG,1,00-0033873,Patrick Mahomes,QB,KC,35,NA,,7.4,-1.1,0\n"));

        assertThat(lines).singleElement().satisfies(line -> assertThat(line.metrics())
                .doesNotContainKeys(NextGenColumn.AVG_TIME_TO_THROW, NextGenColumn.AGGRESSIVENESS)
                .containsEntry(NextGenColumn.COMPLETION_PERCENTAGE_ABOVE_EXPECTATION, 0.0));
    }

    @Test
    void aMetricThatIsNotANumberFailsTheDownloadAsDrift() {
        assertThatThrownBy(() -> Feeds.NGS_PASSING.read(BASIS, csv(HEADER
                + "2026,REG,1,00-0033873,Patrick Mahomes,QB,KC,35,2.8s,15.1,7.4,-1.1,1.5\n")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema drift")
                .hasMessageContaining("avg_time_to_throw");
    }
}
