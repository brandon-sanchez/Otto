package otto.research;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import otto.nflverse.Schedule;

/**
 * How often a team's feed is read. Clubs post practice reports and game
 * statuses on weekday afternoons around the league's 4:00 p.m. Eastern
 * deadline, late changes and roster moves through Saturday afternoon,
 * and inactives 90 minutes before kickoff. Inside those windows a feed
 * is read every few minutes; outside them, once an hour.
 */
final class ReportWindows {

    static final Duration DURING = Duration.ofMinutes(4);

    static final Duration OTHERWISE = Duration.ofHours(1);

    private static final Duration BEFORE_KICKOFF = Duration.ofHours(2);

    private static final ZoneId EASTERN = ZoneId.of("America/New_York");

    private static final LocalTime AFTERNOON_OPENS = LocalTime.of(12, 0);
    private static final LocalTime AFTERNOON_CLOSES = LocalTime.of(20, 0);

    private ReportWindows() {
    }

    /**
     * Returns how long after its last read the team's feed is due again.
     *
     * @param team the Sleeper abbreviation
     * @param games the season's games; empty when the schedule is not
     *        stored, which leaves only the report afternoons
     */
    static Duration interval(String team, Instant now, List<Schedule.Game> games) {
        return reportAfternoon(now) || beforeKickoff(team, now, games) ? DURING : OTHERWISE;
    }

    private static boolean reportAfternoon(Instant now) {
        ZonedDateTime eastern = now.atZone(EASTERN);
        LocalTime time = eastern.toLocalTime();
        return eastern.getDayOfWeek() != DayOfWeek.SUNDAY
                && !time.isBefore(AFTERNOON_OPENS) && time.isBefore(AFTERNOON_CLOSES);
    }

    private static boolean beforeKickoff(String team, Instant now, List<Schedule.Game> games) {
        return games.stream()
                .filter(game -> team.equals(game.home()) || team.equals(game.away()))
                .anyMatch(game -> !now.isBefore(game.kickoff().minus(BEFORE_KICKOFF))
                        && !now.isAfter(game.kickoff()));
    }
}
