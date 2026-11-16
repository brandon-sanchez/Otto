package otto.ask;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import otto.nflverse.Schedule;

/**
 * Which of a player's weeks one stats answer covers: one week by its
 * number, one week counted back from the last, his team's last few
 * games, or the season so far.
 */
sealed interface StatsSpan {

    /** One NFL week by its number. */
    record OneWeek(int week) implements StatsSpan {
    }

    /** One week counted back: 1 is last week, 2 the week before. */
    record WeeksAgo(int weeks) implements StatsSpan {
    }

    /** His team's last few finished games. */
    record LastGames(int games) implements StatsSpan {
    }

    /** Every finished game of the season so far. */
    record Season() implements StatsSpan {
    }

    /** How far a {@link Window} reaches. */
    enum Reach {
        /** One asked-for week, so a bye or a missing week is the answer, not a gap in it. */
        WEEK,
        /** The team's last few games. */
        RECENT,
        /** Every finished game of the season. */
        SEASON
    }

    /** What the tool's arguments asked for, or why they cannot be read. */
    sealed interface Parsed {

        record Ok(StatsSpan span) implements Parsed {
        }

        record Rejected(String reason) implements Parsed {
        }
    }

    /**
     * The weeks a span covers for one team, or why there are none.
     *
     * @param weeks the weeks to read, oldest first; each is a week the
     *        team's game is finished, except a single asked-for week
     * @param label the span in words, such as "week 5 of 2026"
     */
    record Window(List<Integer> weeks, Reach reach, String label) {

        public Window {
            weeks = List.copyOf(weeks);
        }
    }

    /**
     * Reads the tool's arguments. At most one may be given; none means
     * the season so far.
     */
    static Parsed parse(Integer week, Integer weeksAgo, Integer lastGames) {
        long given = Stream.of(week, weeksAgo, lastGames).filter(Objects::nonNull).count();
        if (given > 1) {
            return new Parsed.Rejected("give one of week, weeksAgo or lastGames, not several");
        }
        if (week != null) {
            return week < 1 ? new Parsed.Rejected("week must be 1 or later, not " + week)
                    : new Parsed.Ok(new OneWeek(week));
        }
        if (weeksAgo != null) {
            return weeksAgo < 1 ? new Parsed.Rejected("weeksAgo must be 1 or more, not " + weeksAgo)
                    : new Parsed.Ok(new WeeksAgo(weeksAgo));
        }
        if (lastGames != null) {
            return lastGames < 1
                    ? new Parsed.Rejected("lastGames must be 1 or more, not " + lastGames)
                    : new Parsed.Ok(new LastGames(lastGames));
        }
        return new Parsed.Ok(new Season());
    }

    /**
     * Returns the weeks this span covers for the team in the season.
     *
     * <p>Last week is the newest week every scheduled game of has
     * finished, or the week of the team's newest finished game when that
     * is later, so on the Monday of a week the Sunday games already
     * count. A week counted back that lands on the team's bye is that
     * bye, never the game before it.
     *
     * @param priorSeasonFinal true when the season is last season's final record
     * @return the window, or why the span covers no week, in words fit
     *         to show the user
     */
    default ToolAnswer<Window> window(Schedule schedule, String season,
            boolean priorSeasonFinal, String team) {
        List<Schedule.Game> played = schedule.playedBy(season, team);
        String of = priorSeasonFinal ? " of %s, last season".formatted(season)
                : " of " + season;
        return switch (this) {
            case OneWeek one -> single(schedule, season, team, one.week(), of);
            case WeeksAgo ago -> {
                int last = lastWeek(schedule, season, played);
                if (last == 0) {
                    yield ToolAnswer.unavailable("no week of %s has finished yet"
                            .formatted(season));
                }
                int week = last - ago.weeks() + 1;
                yield week < 1
                        ? ToolAnswer.unavailable("the %s season has only %d finished weeks"
                                .formatted(season, last))
                        : single(schedule, season, team, week, of);
            }
            case LastGames last -> {
                if (played.isEmpty()) {
                    yield noGames(season, team);
                }
                List<Integer> weeks = played.subList(Math.max(0, played.size() - last.games()),
                        played.size()).stream().map(Schedule.Game::week).toList();
                yield ToolAnswer.of(new Window(weeks, Reach.RECENT,
                        "%s, %s's last %d games".formatted(span(weeks, of), team, weeks.size())));
            }
            case Season _ -> {
                if (played.isEmpty()) {
                    yield noGames(season, team);
                }
                List<Integer> weeks = played.stream().map(Schedule.Game::week).toList();
                yield ToolAnswer.of(new Window(weeks, Reach.SEASON,
                        "the season so far, " + span(weeks, of)));
            }
        };
    }

    private static ToolAnswer<Window> single(Schedule schedule, String season, String team,
            int week, String of) {
        List<Schedule.Game> games = schedule.rows().stream()
                .filter(game -> game.season().equals(season) && game.week() == week)
                .toList();
        if (games.isEmpty()) {
            return ToolAnswer.unavailable("the %s schedule has no week %d".formatted(season,
                    week));
        }
        return games.stream()
                .filter(game -> game.home().equals(team) || game.away().equals(team))
                .findFirst()
                .<ToolAnswer<Window>>map(game -> game.finished()
                        ? ToolAnswer.of(new Window(List.of(week), Reach.WEEK,
                                "week %d%s".formatted(week, of)))
                        : ToolAnswer.unavailable("%s's game in week %d is not finished yet"
                                .formatted(team, week)))
                .orElseGet(() -> ToolAnswer.unavailable("%s had a bye in week %d"
                        .formatted(team, week)));
    }

    private static ToolAnswer<Window> noGames(String season, String team) {
        return ToolAnswer.unavailable("%s has no finished game in the %s season"
                .formatted(team, season));
    }

    private static int lastWeek(Schedule schedule, String season, List<Schedule.Game> played) {
        int everyGameFinished = schedule.rows().stream()
                .filter(game -> game.season().equals(season))
                .map(Schedule.Game::week)
                .distinct()
                .filter(week -> schedule.rows().stream()
                        .filter(game -> game.season().equals(season) && game.week() == week)
                        .allMatch(Schedule.Game::finished))
                .max(Comparator.naturalOrder())
                .orElse(0);
        int teamsNewest = played.isEmpty() ? 0 : played.getLast().week();
        return Math.max(everyGameFinished, teamsNewest);
    }

    private static String span(List<Integer> weeks, String of) {
        return weeks.size() == 1 ? "week %d%s".formatted(weeks.getFirst(), of)
                : "weeks %d to %d%s".formatted(weeks.getFirst(), weeks.getLast(), of);
    }
}
