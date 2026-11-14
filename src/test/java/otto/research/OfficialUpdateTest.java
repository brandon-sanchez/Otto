package otto.research;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class OfficialUpdateTest {

    private static final Instant NOW = Instant.parse("2026-09-15T17:00:00Z");
    private static final String TEAM_SITE = "https://49ers.com/news/injury-report";

    private static Optional<OfficialUpdate> read(String headline, String url, Instant published) {
        ResearchItem item = new ResearchItem("49ers:1", "49ers", url, headline, null, null,
                published, NOW, "hash", null, Set.of());
        return OfficialUpdate.latest(new PlayerResearch("4034", List.of(item)), "SF", NOW);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Christian McCaffrey ruled out for Sunday            | WILL_NOT_PLAY
            McCaffrey placed on injured reserve                 | WILL_NOT_PLAY
            McCaffrey is inactive against the Chiefs            | WILL_NOT_PLAY
            McCaffrey will play Sunday                          | WILL_PLAY
            McCaffrey activated from injured reserve            | WILL_PLAY
            """)
    void aPlainStatementFromTheTeamSiteIsAnAnnouncement(String headline,
            OfficialUpdate.Availability expected) {
        assertThat(read(headline, TEAM_SITE, NOW.minusSeconds(60)))
                .map(OfficialUpdate::availability)
                .contains(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            McCaffrey has not been ruled out for Sunday
            McCaffrey hopes to play Sunday
            McCaffrey did not practice Wednesday
            McCaffrey returns to practice
            McCaffrey ruled out of practice but will play Sunday
            """)
    void aHedgedPracticeOrMixedReportIsNoAnnouncement(String headline) {
        assertThat(read(headline, TEAM_SITE, NOW.minusSeconds(60))).isEmpty();
    }

    @Test
    void anotherSiteIsNoAnnouncementEvenWhenItQuotesTheTeam() {
        assertThat(read("49ers say McCaffrey ruled out", "https://rotowire.com/news/1",
                NOW.minusSeconds(60))).isEmpty();
    }

    @Test
    void anAnnouncementOlderThanTheReportWindowNoLongerCounts() {
        assertThat(read("McCaffrey ruled out", TEAM_SITE,
                NOW.minus(PreSendResearch.REPORT_WINDOW).minus(Duration.ofMinutes(1)))).isEmpty();
    }

    @Test
    void anAnnouncementWithNoPublicationTimeNeverCounts() {
        assertThat(read("McCaffrey ruled out", TEAM_SITE, null)).isEmpty();
    }
}
