package otto.research;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ReturnTimelineTest {

    private static final Instant NOW = Instant.parse("2026-11-24T18:00:00Z");

    private static ResearchItem story(String key, String headline, String detail,
            Instant published) {
        return new ResearchItem("rotowire:" + key, "rotowire", "https://example.com/" + key,
                headline, detail, null, published, NOW, "hash-" + key, null, Set.of());
    }

    private static ReturnTimeline read(ResearchItem... items) {
        return ReturnTimeline.from(new PlayerResearch("92001", List.of(items)), NOW);
    }

    private static ReturnTimeline read(String headline, String detail) {
        return read(story("1", headline, detail, NOW.minus(Duration.ofDays(3))));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Bijan Robinson out for the season                 | He tore his ACL on Sunday.
            Robinson suffers season-ending knee injury        | The team confirmed it Monday.
            Robinson to miss the rest of the season           | He needs surgery.
            """)
    void aPlainSeasonEndingReportEndsHisSeason(String headline, String detail) {
        assertThat(read(headline, detail)).isInstanceOf(ReturnTimeline.SeasonEnding.class);
    }

    @Test
    void aHedgedSeasonEndingReportIsNoTimeline() {
        assertThat(read("Robinson could miss the rest of the season",
                "The team fears a torn ACL.")).isInstanceOf(ReturnTimeline.Unknown.class);
        assertThat(read("Robinson avoids a season-ending injury", "Tests came back clean."))
                .isInstanceOf(ReturnTimeline.Unknown.class);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Robinson expected to miss 2-3 weeks          | 2 | 3
            Robinson will miss two to three weeks        | 2 | 3
            Robinson sidelined four weeks with an ankle  | 4 | 4
            Robinson out 4 to 6 weeks                    | 4 | 6
            """)
    void aWeekRangeIsReadWithItsLongestEnd(String headline, int fewest, int most) {
        assertThat(read(headline, null)).isInstanceOfSatisfying(ReturnTimeline.WeeksOut.class,
                weeks -> {
                    assertThat(weeks.fewest()).isEqualTo(fewest);
                    assertThat(weeks.most()).isEqualTo(most);
                    assertThat(weeks.source().outlet()).isEqualTo("rotowire");
                    assertThat(weeks.source().statement()).isEqualTo(headline);
                });
    }

    @Test
    void weeksAlreadyMissedAreNoTimeline() {
        assertThat(read("Robinson has missed the last two weeks", "He remains out."))
                .isInstanceOf(ReturnTimeline.Unknown.class);
    }

    @Test
    void aTargetedReturnWeekIsRead() {
        assertThat(read("Robinson targeting a return in Week 14", null))
                .isInstanceOfSatisfying(ReturnTimeline.ByWeek.class,
                        byWeek -> assertThat(byWeek.week()).isEqualTo(14));
    }

    @Test
    void noTimetableIsSaidWhenTheNewsSaysSo() {
        assertThat(read("Robinson placed on injured reserve",
                "There is no timetable for his return.")).isInstanceOf(
                        ReturnTimeline.NoTimetable.class);
        assertThat(read("Robinson out indefinitely", null))
                .isInstanceOf(ReturnTimeline.NoTimetable.class);
    }

    @Test
    void newsThatStatesNoTimelineLeavesItUnknown() {
        assertThat(read("Robinson did not practice Wednesday", "He has a knee injury."))
                .isInstanceOf(ReturnTimeline.Unknown.class);
        assertThat(read()).isInstanceOf(ReturnTimeline.Unknown.class);
    }

    @Test
    void theNewestStoryThatStatesATimelineWins() {
        ResearchItem older = story("1", "Robinson expected to miss 2-3 weeks", null,
                NOW.minus(Duration.ofDays(10)));
        ResearchItem newer = story("2", "Robinson out for the season after setback", null,
                NOW.minus(Duration.ofDays(2)));
        ResearchItem newest = story("3", "Robinson did not practice", null,
                NOW.minus(Duration.ofDays(1)));

        assertThat(read(older, newer, newest)).isInstanceOf(ReturnTimeline.SeasonEnding.class);
    }

    @Test
    void aStoryFromBeforeTheSeasonOrACopyIsNotRead() {
        ResearchItem stale = story("1", "Robinson out for the season", null,
                NOW.minus(Duration.ofDays(200)));
        ResearchItem copy = new ResearchItem("espn:9", "espn", null,
                "Robinson out for the season", null, null, NOW.minus(Duration.ofDays(1)), NOW,
                "hash-9", "rotowire:0", Set.of());

        assertThat(read(stale, copy)).isInstanceOf(ReturnTimeline.Unknown.class);
    }
}
