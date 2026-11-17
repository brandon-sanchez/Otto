package otto.board;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import otto.check.SeasonWeek;
import otto.league.LeagueWeek;
import otto.nflverse.Coverage;
import otto.nflverse.FeedId;
import otto.nflverse.NflverseReadiness;
import otto.nflverse.NflverseStore;
import otto.nflverse.Schedule;
import otto.nflverse.WeekStatus;
import otto.sleeper.SleeperAdapter;
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
 * <p>Once the board can wait no longer, Sleeper's stats endpoint backs
 * up the two required feeds: a team whose weekly stats or snap counts
 * nflverse lacks is filled from Sleeper when Sleeper holds that team's
 * game, so the normal board is built. nflverse stays the primary source:
 * a team it holds is never filled, and nothing from Sleeper is stored,
 * so every read after nflverse arrives uses nflverse alone.
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
    private final NflverseStore nflverse;
    private final SleeperAdapter sleeper;

    public LatestWeekData(NflverseReadiness readiness, NflverseStore nflverse,
            SleeperAdapter sleeper) {
        this.readiness = readiness;
        this.nflverse = nflverse;
        this.sleeper = sleeper;
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

        private static Report unchecked(List<String> missing) {
            return new Report(OptionalInt.empty(), missing, List.of(), List.of(),
                    SleeperFill.NONE);
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

    /** What Sleeper filled: one sentence per filled feed, and the lines. */
    private record Fill(Map<FeedId, String> filled, SleeperFill sleeper) {

        static final Fill NOTHING = new Fill(Map.of(), SleeperFill.NONE);
    }

    /**
     * Assesses the latest completed week for the league's season from
     * nflverse alone.
     *
     * <p>Side effects: reads the stored schedule and each feed's stored
     * document.
     */
    public Report assess(LeagueWeek league) {
        return assess(league, false);
    }

    /**
     * Assesses the latest completed week as {@link #assess} does, then
     * fills each required feed's gap from Sleeper's stats when Sleeper
     * holds every game the feed lacks. A feed Sleeper cannot fill in
     * full stays missing.
     *
     * <p>Side effects: the reads {@link #assess} makes, and one Sleeper
     * stats request when a required feed lacks part of the week.
     */
    public Report assessFillingFromSleeper(LeagueWeek league) {
        return assess(league, true);
    }

    private Report assess(LeagueWeek league, boolean fillFromSleeper) {
        if (league.week().seasonWeek().isEmpty()) {
            return Report.unchecked(List.of("Sleeper's NFL week could not be "
                    + "read, so the latest week's data cannot be checked"));
        }
        SeasonWeek current = league.week().seasonWeek().get();
        int owed = Math.max(readiness.latestCompletedWeek(current.season()).orElse(0),
                current.number() - 1);
        if (owed < 1) {
            return Report.unchecked(List.of());
        }
        Map<FeedId, String> missing = new EnumMap<>(FeedId.class);
        Map<FeedId, WeekStatus.Incomplete> shortFeeds = new EnumMap<>(FeedId.class);
        REQUIRED.forEach((feed, name) -> {
            switch (readiness.weekStatus(feed, current.season(), owed)) {
                case WeekStatus.Complete _ -> { }
                case WeekStatus.Incomplete incomplete -> {
                    missing.put(feed, lacks(name, owed, incomplete));
                    shortFeeds.put(feed, incomplete);
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
                optionalMissing.add(lacks(name, owed, incomplete));
            }
        });
        Fill fill = fillFromSleeper && !shortFeeds.isEmpty()
                ? fill(current.season(), owed, shortFeeds)
                : Fill.NOTHING;
        fill.filled().keySet().forEach(missing::remove);
        return new Report(OptionalInt.of(owed), List.copyOf(missing.values()), optionalMissing,
                List.copyOf(fill.filled().values()), fill.sleeper());
    }

    private Fill fill(String season, int week, Map<FeedId, WeekStatus.Incomplete> shortFeeds) {
        List<Schedule.Game> games = nflverse.schedule().map(Schedule::rows).orElse(List.of())
                .stream()
                .filter(game -> game.season().equals(season) && game.week() == week)
                .toList();
        Map<String, Map<String, Double>> lines = switch (sleeper.stats(season, week)) {
            case SourceResult.Ok<Map<String, Map<String, Double>>> ok -> ok.value();
            case SourceResult.Unavailable<Map<String, Map<String, Double>>> down -> {
                log.warn("Sleeper's week {} stats could not back up nflverse: {}", week,
                        down.reason());
                yield Map.of();
            }
        };
        Map<FeedId, String> filled = new EnumMap<>(FeedId.class);
        Map<FeedId, Set<String>> teams = new EnumMap<>(FeedId.class);
        shortFeeds.forEach((feed, incomplete) -> teamsLacking(incomplete, games)
                .filter(lacking -> lacking.stream()
                        .allMatch(team -> SleeperFill.holds(lines, team)))
                .ifPresent(lacking -> {
                    teams.put(feed, lacking);
                    filled.put(feed, "%s for %s come from Sleeper's stats, because nflverse "
                            .formatted(REQUIRED.get(feed), units(week, incomplete))
                            + "had not published them when the board was built");
                }));
        if (filled.isEmpty()) {
            return Fill.NOTHING;
        }
        Map<String, String> opponents = new HashMap<>();
        games.forEach(game -> {
            opponents.put(game.home(), game.away());
            opponents.put(game.away(), game.home());
        });
        return new Fill(filled, new SleeperFill(week,
                teams.getOrDefault(FeedId.WEEKLY_STATS, Set.of()),
                teams.getOrDefault(FeedId.SNAP_COUNTS, Set.of()), opponents, lines));
    }

    /**
     * Returns the teams whose games the feed lacks, empty when a missing
     * unit names no team the schedule can place.
     */
    private static Optional<Set<String>> teamsLacking(WeekStatus.Incomplete incomplete,
            List<Schedule.Game> games) {
        Map<String, Schedule.Game> byId = games.stream()
                .collect(Collectors.toMap(Schedule.Game::gameId, Function.identity()));
        Set<String> teams = new LinkedHashSet<>();
        for (Coverage.Unit unit : incomplete.missing()) {
            switch (unit) {
                case Coverage.Unit.Game game -> {
                    Schedule.Game scheduled = byId.get(game.gameId());
                    if (scheduled == null) {
                        return Optional.empty();
                    }
                    teams.add(scheduled.away());
                    teams.add(scheduled.home());
                }
                case Coverage.Unit.TeamWeek teamWeek -> teams.add(teamWeek.team());
                case Coverage.Unit.Team team -> teams.add(team.team());
                case Coverage.Unit.Week _ -> games.forEach(game -> {
                    teams.add(game.away());
                    teams.add(game.home());
                });
                case Coverage.Unit.File _ -> {
                    return Optional.empty();
                }
            }
        }
        return teams.isEmpty() ? Optional.empty() : Optional.of(teams);
    }

    private static String lacks(String feed, int week, WeekStatus.Incomplete incomplete) {
        return feed + " lack " + units(week, incomplete);
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
