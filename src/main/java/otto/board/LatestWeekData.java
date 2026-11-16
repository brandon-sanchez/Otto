package otto.board;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import org.springframework.stereotype.Component;

import otto.ask.LeagueWeek;
import otto.check.SeasonWeek;
import otto.nflverse.Coverage;
import otto.nflverse.FeedId;
import otto.nflverse.NflverseReadiness;
import otto.nflverse.WeekStatus;

/**
 * Whether the latest completed NFL week has reached the nflverse feeds
 * the waiver board reads, so a board is never built on an older week's
 * numbers as if they were the latest.
 *
 * <p>The week owed is the newer of the schedule's latest completed week
 * and the week before Sleeper's current one. A schedule whose results
 * lag behind Sleeper still owes the week Sleeper has moved past, and
 * that week's games then read as unfinished, which holds the board
 * rather than letting an older complete week pass for the latest.
 *
 * <p>The weekly stats and the snap counts carry every candidate's
 * production and role, so the board waits for them. The play-by-play,
 * the FTN charting and the expected points add optional metrics the
 * board reads as unknown when absent, so their gaps are reported and
 * never waited for.
 */
@Component
public class LatestWeekData {

    private static final Map<FeedId, String> REQUIRED = new EnumMap<>(Map.of(
            FeedId.WEEKLY_STATS, "the weekly stats",
            FeedId.SNAP_COUNTS, "the snap counts"));

    private static final Map<FeedId, String> OPTIONAL = new EnumMap<>(Map.of(
            FeedId.PLAY_BY_PLAY, "the play-by-play",
            FeedId.FTN_CHARTING, "the FTN charting",
            FeedId.EXPECTED_POINTS, "the expected points"));

    private final NflverseReadiness readiness;

    public LatestWeekData(NflverseReadiness readiness) {
        this.readiness = readiness;
    }

    /**
     * What the board knows about the latest completed week's data.
     *
     * @param week the week owed; empty when no week of the season has
     *        been played, or when Sleeper's season is unknown
     * @param missing one sentence per required feed that does not hold
     *        the week in full, or the reason the week cannot be checked;
     *        the board waits while any is listed
     * @param optionalMissing one sentence per optional feed that lacks
     *        part of the week
     */
    public record Report(OptionalInt week, List<String> missing, List<String> optionalMissing) {

        public Report {
            missing = List.copyOf(missing);
            optionalMissing = List.copyOf(optionalMissing);
        }

        /** Returns whether every required feed holds the week owed. */
        public boolean complete() {
            return missing.isEmpty();
        }

        /** Returns every gap, required first, as sentences for the model. */
        List<String> gaps() {
            List<String> gaps = new ArrayList<>(missing);
            gaps.addAll(optionalMissing);
            return gaps;
        }

        /**
         * Returns the note a board built without the full week opens
         * with: what is missing and what the advice rests on instead.
         * Meaningful only when the report is not complete.
         */
        String notice() {
            String basis = week.isEmpty()
                    ? "the numbers that have arrived"
                    : week.getAsInt() == 1 ? "the week 1 numbers that have arrived"
                    : "the week %d numbers that have arrived, the weeks before it"
                            .formatted(week.getAsInt());
            String opening = week.isEmpty() ? "The latest week's numbers cannot be confirmed"
                    : "Week %d's numbers are not all in".formatted(week.getAsInt());
            return ("%s: %s. This board is based on %s, this week's projections and the "
                    + "latest news. Any pickup only trending adds support is marked: he is "
                    + WaiverEvidence.TRENDING_ONLY + ".")
                    .formatted(opening, String.join("; ", missing), basis);
        }
    }

    /**
     * Assesses the latest completed week for the league's season.
     *
     * <p>Side effects: reads the stored schedule and each feed's stored
     * document.
     */
    public Report assess(LeagueWeek league) {
        if (league.week().seasonWeek().isEmpty()) {
            return new Report(OptionalInt.empty(), List.of("Sleeper's NFL week could not be "
                    + "read, so the latest week's data cannot be checked"), List.of());
        }
        SeasonWeek current = league.week().seasonWeek().get();
        int owed = Math.max(readiness.latestCompletedWeek(current.season()).orElse(0),
                current.number() - 1);
        if (owed < 1) {
            return new Report(OptionalInt.empty(), List.of(), List.of());
        }
        List<String> missing = new ArrayList<>();
        REQUIRED.forEach((feed, name) -> {
            switch (readiness.weekStatus(feed, current.season(), owed)) {
                case WeekStatus.Complete _ -> { }
                case WeekStatus.Incomplete incomplete -> missing.add(lacks(name, owed, incomplete));
                case WeekStatus.Unknown unknown -> missing.add(
                        "%s cannot be checked for week %d: %s"
                                .formatted(name, owed, unknown.reason()));
            }
        });
        List<String> optionalMissing = new ArrayList<>();
        OPTIONAL.forEach((feed, name) -> {
            if (readiness.weekStatus(feed, current.season(), owed)
                    instanceof WeekStatus.Incomplete incomplete) {
                optionalMissing.add(lacks(name, owed, incomplete));
            }
        });
        return new Report(OptionalInt.of(owed), missing, optionalMissing);
    }

    private static String lacks(String feed, int week, WeekStatus.Incomplete incomplete) {
        List<String> games = new ArrayList<>();
        List<String> teams = new ArrayList<>();
        for (Coverage.Unit unit : incomplete.missing()) {
            switch (unit) {
                case Coverage.Unit.Game game -> games.add(matchup(game.gameId()));
                case Coverage.Unit.TeamWeek teamWeek -> teams.add(teamWeek.team());
                case Coverage.Unit.Team team -> teams.add(team.team());
                case Coverage.Unit.File _ -> { }
            }
        }
        List<String> parts = new ArrayList<>();
        if (!games.isEmpty()) {
            parts.add("week %d's %s game%s".formatted(week, String.join(", ", games),
                    games.size() == 1 ? "" : "s"));
        }
        if (!teams.isEmpty()) {
            parts.add("week %d for %s".formatted(week, String.join(", ", teams)));
        }
        return feed + " lack " + String.join(" and ", parts);
    }

    /**
     * Returns an nflverse game id such as {@code 2026_01_BUF_MIA} as
     * "BUF at MIA"; nflverse writes the away team before the home team.
     */
    private static String matchup(String gameId) {
        String[] parts = gameId.split("_");
        return parts.length == 4 ? parts[2] + " at " + parts[3] : gameId;
    }
}
