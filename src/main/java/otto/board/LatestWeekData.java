package otto.board;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import otto.check.SeasonWeek;
import otto.league.LeagueWeek;
import otto.nflverse.Coverage;
import otto.nflverse.FeedId;
import otto.nflverse.NflverseReadiness;
import otto.nflverse.WeekStatus;
import otto.sleeper.SourceResult;

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
 *
 * <p>Once the board can wait no longer, {@link SleeperBackup} backs up
 * the two required feeds where nflverse holds part of the week: a feed
 * Sleeper fills in full no longer counts as missing, so the normal board
 * is built. nflverse stays the primary source: a team it holds is never
 * filled, and nothing from Sleeper is stored, so every read after
 * nflverse arrives uses nflverse alone. A feed that holds another season
 * or was never downloaded is not filled, since Otto cannot tell which
 * teams it lacks.
 */
@Component
public class LatestWeekData {

    private static final Logger log = LoggerFactory.getLogger(LatestWeekData.class);

    private static final Map<FeedId, String> REQUIRED = new EnumMap<>(Map.of(
            FeedId.WEEKLY_STATS, "the weekly stats",
            FeedId.SNAP_COUNTS, "the snap counts"));

    private static final Map<FeedId, String> OPTIONAL = new EnumMap<>(Map.of(
            FeedId.PLAY_BY_PLAY, "the play-by-play",
            FeedId.FTN_CHARTING, "the FTN charting",
            FeedId.EXPECTED_POINTS, "the expected points"));

    private final NflverseReadiness readiness;
    private final SleeperBackup backup;

    public LatestWeekData(NflverseReadiness readiness, SleeperBackup backup) {
        this.readiness = readiness;
        this.backup = backup;
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
     *        part of the week, and why Sleeper could not back up a late
     *        required feed when it could not be read
     * @param filledFromSleeper one sentence per required feed whose gap
     *        Sleeper filled, naming the games or teams it filled
     * @param sleeper the lines that fill those gaps; left out of the
     *        Decision Record, whose evidence names each value's source
     */
    public record Report(OptionalInt week, List<String> missing, List<String> optionalMissing,
            List<String> filledFromSleeper, @JsonIgnore SleeperFill sleeper) {

        public Report {
            missing = List.copyOf(missing);
            optionalMissing = List.copyOf(optionalMissing);
            filledFromSleeper = List.copyOf(filledFromSleeper);
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
            String opening = week.isEmpty() ? "I can't confirm the latest week's numbers yet"
                    : "Week %d's numbers aren't all in yet".formatted(week.getAsInt());
            return ("%s, because %s. So this board uses %s, this week's projections and the "
                    + "latest news, and I've marked any pickup who is on it only because "
                    + "he's trending in adds.")
                    .formatted(opening, String.join(", and ", missing), basis);
        }
    }

    /**
     * Each feed's answer for the week owed, before Sleeper fills any.
     *
     * @param season the season owed; null when the week cannot be checked
     * @param missing the sentence for each required feed that does not
     *        hold the week
     * @param unchecked why the week as a whole cannot be checked, when it cannot
     * @param lacking the required feeds that hold part of the week
     */
    private record Statuses(String season, OptionalInt week, Map<FeedId, String> missing,
            List<String> unchecked, Map<FeedId, WeekStatus.Incomplete> lacking,
            List<String> optionalMissing) {

        static Statuses unchecked(List<String> reasons) {
            return new Statuses(null, OptionalInt.empty(), Map.of(), reasons, Map.of(),
                    List.of());
        }

        Report report() {
            return filledBy(SleeperFill.NONE, List.of());
        }

        Report filledBy(SleeperFill fill, List<String> notes) {
            List<String> stillMissing = new ArrayList<>(unchecked);
            missing.forEach((feed, sentence) -> {
                if (!fill.feeds().contains(feed)) {
                    stillMissing.add(sentence);
                }
            });
            List<String> optional = new ArrayList<>(optionalMissing);
            optional.addAll(notes);
            List<String> filled = fill.feeds().stream()
                    .map(feed -> "%s for %s come from Sleeper's stats, because nflverse had "
                            .formatted(REQUIRED.get(feed), units(fill.week(), lacking.get(feed)))
                            + "not published them when the board was built")
                    .toList();
            return new Report(week, stillMissing, optional, filled, fill);
        }
    }

    /**
     * Assesses the latest completed week for the league's season from
     * nflverse alone.
     *
     * <p>Side effects: reads the stored schedule and each feed's stored
     * document.
     */
    public Report assess(LeagueWeek league) {
        return statuses(league).report();
    }

    /**
     * Assesses the latest completed week as {@link #assess} does, with
     * each required feed that lacks part of the week filled from
     * Sleeper's stats where {@link SleeperBackup} can fill it in full.
     *
     * <p>Side effects: the reads {@link #assess} makes, and one Sleeper
     * stats request when a required feed lacks part of the week.
     */
    public Report assessFillingFromSleeper(LeagueWeek league) {
        Statuses statuses = statuses(league);
        if (statuses.lacking().isEmpty()) {
            return statuses.report();
        }
        int week = statuses.week().getAsInt();
        return switch (backup.fill(statuses.season(), week, statuses.lacking())) {
            case SourceResult.Ok<SleeperFill> ok -> statuses.filledBy(ok.value(), List.of());
            case SourceResult.Unavailable<SleeperFill> down -> {
                log.warn("Sleeper's week {} stats could not back up nflverse: {}", week,
                        down.reason());
                yield statuses.filledBy(SleeperFill.NONE, List.of(
                        "Sleeper's stats could not stand in for the late week %d data: %s"
                                .formatted(week, down.reason())));
            }
        };
    }

    private Statuses statuses(LeagueWeek league) {
        if (league.week().seasonWeek().isEmpty()) {
            return Statuses.unchecked(List.of("Sleeper's NFL week could not be "
                    + "read, so the latest week's data cannot be checked"));
        }
        SeasonWeek current = league.week().seasonWeek().get();
        int owed = Math.max(readiness.latestCompletedWeek(current.season()).orElse(0),
                current.number() - 1);
        if (owed < 1) {
            return Statuses.unchecked(List.of());
        }
        Map<FeedId, String> missing = new EnumMap<>(FeedId.class);
        Map<FeedId, WeekStatus.Incomplete> lacking = new EnumMap<>(FeedId.class);
        REQUIRED.forEach((feed, name) -> {
            switch (readiness.weekStatus(feed, current.season(), owed)) {
                case WeekStatus.Complete _ -> { }
                case WeekStatus.Incomplete incomplete -> {
                    missing.put(feed, name + " lack " + units(owed, incomplete));
                    lacking.put(feed, incomplete);
                }
                case WeekStatus.Unknown unknown -> missing.put(feed,
                        "%s cannot be checked for week %d: %s"
                                .formatted(name, owed, unknown.reason()));
            }
        });
        List<String> optionalMissing = new ArrayList<>();
        OPTIONAL.forEach((feed, name) -> {
            if (readiness.weekStatus(feed, current.season(), owed)
                    instanceof WeekStatus.Incomplete incomplete) {
                optionalMissing.add(name + " lack " + units(owed, incomplete));
            }
        });
        return new Statuses(current.season(), OptionalInt.of(owed), missing, List.of(),
                lacking, optionalMissing);
    }

    /** Returns the units a feed lacks in words, such as "week 1's BUF at MIA game". */
    private static String units(int week, WeekStatus.Incomplete incomplete) {
        List<String> games = new ArrayList<>();
        List<String> teams = new ArrayList<>();
        boolean wholeWeek = false;
        for (Coverage.Unit unit : incomplete.missing()) {
            switch (unit) {
                case Coverage.Unit.Game game -> games.add(matchup(game.gameId()));
                case Coverage.Unit.TeamWeek teamWeek -> teams.add(teamWeek.team());
                case Coverage.Unit.Team team -> teams.add(team.team());
                case Coverage.Unit.Week _ -> wholeWeek = true;
                case Coverage.Unit.File _ -> { }
            }
        }
        List<String> parts = new ArrayList<>();
        if (wholeWeek) {
            parts.add("week %d".formatted(week));
        }
        if (!games.isEmpty()) {
            parts.add("week %d's %s game%s".formatted(week, String.join(", ", games),
                    games.size() == 1 ? "" : "s"));
        }
        if (!teams.isEmpty()) {
            parts.add("week %d for %s".formatted(week, String.join(", ", teams)));
        }
        return String.join(" and ", parts);
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
