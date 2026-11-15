package otto.research;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import otto.nflverse.Schedule;

import static org.assertj.core.api.Assertions.assertThat;

class ReportWindowsTest {

    private static final Instant KICKOFF = Instant.parse("2026-11-15T18:00:00Z");

    private static final List<Schedule.Game> GAMES = List.of(
            new Schedule.Game("2026_10_KC_ATL", "2026", 10, KICKOFF, "ATL", "KC", false));

    @Test
    void aTeamIsWatchedCloselyFromTwoHoursBeforeItsKickoffUntilKickoff() {
        assertThat(ReportWindows.interval("KC", KICKOFF.minusSeconds(2 * 3600 + 60), GAMES))
                .isEqualTo(ReportWindows.OTHERWISE);
        assertThat(ReportWindows.interval("KC", KICKOFF.minusSeconds(90 * 60), GAMES))
                .isEqualTo(ReportWindows.DURING);
        assertThat(ReportWindows.interval("ATL", KICKOFF, GAMES))
                .isEqualTo(ReportWindows.DURING);
        assertThat(ReportWindows.interval("KC", KICKOFF.plusSeconds(60), GAMES))
                .isEqualTo(ReportWindows.OTHERWISE);
    }

    @Test
    void aTeamNotPlayingInTheNextTwoHoursIsReadHourlyOnSunday() {
        assertThat(ReportWindows.interval("PIT", KICKOFF.minusSeconds(90 * 60), GAMES))
                .isEqualTo(ReportWindows.OTHERWISE);
    }

    @Test
    void everyTeamIsWatchedCloselyOnAReportAfternoon() {
        Instant fridayFourPmEastern = Instant.parse("2026-11-13T21:00:00Z");
        assertThat(ReportWindows.interval("PIT", fridayFourPmEastern, List.of()))
                .isEqualTo(ReportWindows.DURING);
        Instant fridayMorningEastern = Instant.parse("2026-11-13T14:00:00Z");
        assertThat(ReportWindows.interval("PIT", fridayMorningEastern, List.of()))
                .isEqualTo(ReportWindows.OTHERWISE);
        Instant sundayFourPmEastern = Instant.parse("2026-11-15T21:00:00Z");
        assertThat(ReportWindows.interval("PIT", sundayFourPmEastern, List.of()))
                .isEqualTo(ReportWindows.OTHERWISE);
    }
}
