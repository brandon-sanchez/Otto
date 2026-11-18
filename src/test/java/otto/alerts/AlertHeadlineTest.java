package otto.alerts;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class AlertHeadlineTest {

    private static AlertCandidate candidate(AlertCandidate.Source source, String player,
            Map<String, String> facts) {
        return new AlertCandidate(source, "key", "4034", "TB",
                new Recommendation("4034", player, "action", Confidence.MEDIUM, List.of(),
                        List.of()), facts);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "OUT|🔴 <b>Bucky Irving</b> is now Out",
            "IR|🔴 <b>Bucky Irving</b> is now on IR",
            "QUESTIONABLE|🟡 <b>Bucky Irving</b> is now Questionable",
            "DOUBTFUL|🟠 <b>Bucky Irving</b> is now Doubtful",
            "ACTIVE|✅ <b>Bucky Irving</b> is now Active",
            "NOT_ACTIVE|🔔 <b>Bucky Irving</b> is now Not active",
            "SOMETHING_NEW|🔔 <b>Bucky Irving</b> has news"})
    void aStatusChangeTakesTheDotOfItsNewStatus(String to, String headline) {
        assertThat(AlertHeadline.of(candidate(AlertCandidate.Source.TRANSITION, "Bucky Irving",
                Map.of("to", to)))).isEqualTo(headline);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "false|🔴 <b>Bucky Irving</b> won't play, his team says",
            "true|✅ <b>Bucky Irving</b> will play, his team says"})
    void aTeamAnnouncementSaysWhatTheTeamSaidNotSleepersDesignation(String plays,
            String headline) {
        assertThat(AlertHeadline.of(candidate(AlertCandidate.Source.TRANSITION, "Bucky Irving",
                Map.of("to", "ACTIVE", OfficialUpdateDetector.TEAM_SAYS_PLAYS, plays))))
                .isEqualTo(headline);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "ROLE_SLIP|📉 <b>Bucky Irving</b>'s role is slipping",
            "DROP|📉 <b>Bucky Irving</b> was dropped in your league",
            "TRADE|🔁 <b>Trade in your league</b>",
            "WATCHLIST|👀 <b>Bucky Irving</b>, on your watchlist",
            "LEGALITY|⚠️ <b>Your lineup has a problem</b>",
            "EDGE|⬆️ <b>Your lineup can score more</b>"})
    void eachKindOfNewsHasItsOwnIcon(AlertCandidate.Source source, String headline) {
        assertThat(AlertHeadline.of(candidate(source, "Bucky Irving", Map.of())))
                .isEqualTo(headline);
    }

    @Test
    void aNameThatLooksLikeHtmlIsEscaped() {
        assertThat(AlertHeadline.of(candidate(AlertCandidate.Source.WATCHLIST, "A<b>&B",
                Map.of()))).isEqualTo("👀 <b>A&lt;b&gt;&amp;B</b>, on your watchlist");
    }

    @Test
    void theFinalWarningCountsTheWholeMinutesLeft() {
        Instant kickoff = Instant.parse("2026-09-13T17:00:00Z");

        assertThat(AlertHeadline.finalWarning(kickoff, kickoff.minusSeconds(30 * 60)))
                .isEqualTo("⏰ <b>Locks in 30 min</b>");
        assertThat(AlertHeadline.finalWarning(kickoff, kickoff.minusSeconds(150)))
                .isEqualTo("⏰ <b>Locks in 2 min</b>");
        assertThat(AlertHeadline.finalWarning(kickoff, kickoff))
                .isEqualTo("⏰ <b>Locks in 1 min</b>");
    }
}
