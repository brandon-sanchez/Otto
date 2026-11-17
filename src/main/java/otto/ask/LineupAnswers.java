package otto.ask;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

import org.springframework.stereotype.Component;

import otto.advice.ToolAnswer;
import otto.league.UserWeek;
import otto.lineup.GameWeek;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;

/**
 * Answers lineup questions for the week the user means: this week by
 * default, next week, or a week he names. A question about "this week"
 * after its last game has finished is about next week, because that is
 * the only lineup left to set.
 */
@Component
public class LineupAnswers {

    /** The last week of Sleeper's regular-season projections. */
    private static final int LAST_PROJECTED_WEEK = 18;

    private static final String NEXT = "next";
    private static final List<String> THIS = List.of("", "this", "current");

    private final UserWeekLoader loader;
    private final LineupPlanner planner;

    public LineupAnswers(UserWeekLoader loader, LineupPlanner planner) {
        this.loader = loader;
        this.planner = planner;
    }

    /** The week a question covers, or why Otto cannot plan it. */
    private sealed interface Target {

        /**
         * @param finishedWeek the week that just ended when the user
         *        asked about it, so the answer can say why it covers the
         *        next one; null when he got the week he asked for
         */
        record Week(UserWeek team, int number, Integer finishedWeek) implements Target {
        }

        record Rejected(String reason) implements Target {
        }
    }

    /**
     * Returns the recommended lineup for the week the user asked about,
     * with the answer he reads already written into its message.
     *
     * @param week "next", a week number, or null or blank for this week
     */
    public ToolAnswer<LineupPlanner.LineupPlan> recommend(String week, Instant now) {
        return switch (target(week)) {
            case Target.Rejected rejected -> ToolAnswer.unavailable(rejected.reason());
            case Target.Week target -> {
                ToolAnswer<LineupPlanner.LineupPlan> answer = planner.recommend(target.team(), now);
                yield answer.facts() == null
                        ? answer
                        : ToolAnswer.of(answer.facts().withMessage(LineupMessage.write(
                                target.team(), answer.facts(), now, target.number(),
                                target.finishedWeek())));
            }
        };
    }

    /**
     * Prices one lineup change in the week the user asked about.
     *
     * @param week "next", a week number, or null or blank for this week
     */
    public ToolAnswer<LineupPlanner.WhatIf> whatIf(String week, Instant now, String start,
            String sit) {
        return switch (target(week)) {
            case Target.Rejected rejected -> ToolAnswer.unavailable(rejected.reason());
            case Target.Week target -> planner.whatIf(target.team(), now, start, sit);
        };
    }

    private Target target(String week) {
        return switch (loader.load()) {
            case SourceResult.Unavailable<UserWeek> unavailable -> unavailable(unavailable);
            case SourceResult.Ok<UserWeek> ok -> target(week, ok.value());
        };
    }

    private Target target(String week, UserWeek current) {
        OptionalInt thisWeek = current.week().weekNumber();
        if (thisWeek.isEmpty()) {
            return new Target.Rejected(
                    "I can't tell which NFL week it is right now, so I can't plan a lineup");
        }

        String asked = week == null ? "" : week.trim().toLowerCase(Locale.ROOT);
        int number;
        if (THIS.contains(asked)) {
            number = thisWeek.getAsInt();
        } else if (NEXT.equals(asked)) {
            number = thisWeek.getAsInt() + 1;
        } else if (asked.matches("\\d{1,2}")) {
            number = Integer.parseInt(asked);
        } else {
            return new Target.Rejected(("I can't tell which week \"%s\" means. Ask about this "
                    + "week, next week or a week number").formatted(week));
        }

        if (number < thisWeek.getAsInt()) {
            return new Target.Rejected(
                    "week %d is already over, so there's no lineup left to set for it"
                            .formatted(number));
        }
        if (number == thisWeek.getAsInt()) {
            return finished(current)
                    ? load(number + 1, number)
                    : withProjections(new Target.Week(current, number, null));
        }
        return load(number, null);
    }

    private Target load(int number, Integer finishedWeek) {
        if (number > LAST_PROJECTED_WEEK) {
            return new Target.Rejected(
                    "the regular season ends in week %d, and I only plan lineups up to then"
                            .formatted(LAST_PROJECTED_WEEK));
        }
        return switch (loader.load(number)) {
            case SourceResult.Unavailable<UserWeek> unavailable -> unavailable(unavailable);
            case SourceResult.Ok<UserWeek> ok ->
                withProjections(new Target.Week(ok.value(), number, finishedWeek));
        };
    }

    private static Target withProjections(Target.Week target) {
        boolean unpublished = target.team().week().projections()
                .map(table -> !table.projectsAnyone())
                .orElse(false);
        return unpublished
                ? new Target.Rejected(("Sleeper hasn't published week %d projections yet, so I "
                        + "can't plan that lineup. Ask me again closer to the week")
                        .formatted(target.number()))
                : target;
    }

    private static boolean finished(UserWeek team) {
        List<SleeperAdapter.Game> schedule = team.week().games()
                .map(GameWeek::schedule)
                .orElse(List.of());
        return !schedule.isEmpty() && schedule.stream().allMatch(GameWeek::complete);
    }

    private static Target unavailable(SourceResult.Unavailable<UserWeek> unavailable) {
        return new Target.Rejected("%s: %s".formatted(unavailable.source(), unavailable.reason()));
    }
}
