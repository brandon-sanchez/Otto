package otto.ask;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;

/**
 * The tools the Lane A agent loop routes questions to. Each one runs
 * deterministic Java and hands back computed facts; the model chooses
 * which to call and writes the words, never the numbers.
 *
 * This is the first four of the twelve tools the spec names. Keep them
 * coarse: one tool per question a user would ask, not one per field.
 */
@Component
public class AskTools {

    private final UserWeekLoader loader;
    private final LineupPlanner planner;
    private final Clock clock;
    private final double edgeThreshold;

    public AskTools(UserWeekLoader loader, LineupPlanner planner, Clock clock,
            OttoProperties properties) {
        this.loader = loader;
        this.planner = planner;
        this.clock = clock;
        this.edgeThreshold = properties.edgeThreshold();
    }

    /** The league's rules, as the projection and lineup math applies them. */
    public record LeagueSettings(String league, String status, List<String> rosterPositions,
            Map<String, Double> scoring, String edgeThreshold) {
    }

    @Tool(name = "get_roster_status", description = """
            The user's current roster this week: every starting slot with
            who fills it, their projected points and what stops them
            scoring, plus the bench with the same detail. Call this for
            any question about who is on the team or how they look.""")
    public ToolAnswer<LineupPlanner.RosterStatus> getRosterStatus() {
        return switch (loader.load()) {
            case SourceResult.Unavailable<UserWeek> unavailable -> unavailable(unavailable);
            case SourceResult.Ok<UserWeek> ok ->
                ToolAnswer.of(planner.rosterStatus(ok.value(), clock.instant()));
        };
    }

    @Tool(name = "get_league_settings", description = """
            The league's scoring settings, starting slots and status.
            Call this whenever the answer depends on how the league
            scores or which slots must be filled.""")
    public ToolAnswer<LeagueSettings> getLeagueSettings() {
        return switch (loader.league()) {
            case SourceResult.Unavailable<SleeperAdapter.League> unavailable ->
                unavailable(unavailable);
            case SourceResult.Ok<SleeperAdapter.League> ok -> ToolAnswer.of(new LeagueSettings(
                    ok.value().name(),
                    ok.value().status(),
                    ok.value().rosterPositions(),
                    ok.value().scoringSettings(),
                    String.format(Locale.ROOT, "%.1f", edgeThreshold)));
        };
    }

    @Tool(name = "recommend_lineup", description = """
            The optimal legal lineup for this week with the projected
            point delta against the lineup the user has set now, and the
            concrete start-over-sit swaps that get there. Call this for
            "lineup", start/sit questions, and any request for the best
            lineup.""")
    public ToolAnswer<LineupPlanner.LineupPlan> recommendLineup() {
        return switch (loader.load()) {
            case SourceResult.Unavailable<UserWeek> unavailable -> unavailable(unavailable);
            case SourceResult.Ok<UserWeek> ok -> planner.recommend(ok.value(), clock.instant());
        };
    }

    @Tool(name = "whatif_lineup", description = """
            Prices one hypothetical lineup change before the user acts:
            the projected total with the change, the delta against the
            current lineup, and whether the league would take it. Call
            this for any "what if I start X" question.""")
    public ToolAnswer<LineupPlanner.WhatIf> whatifLineup(
            @ToolParam(description = "The player to start, by name or Sleeper player id")
            String start,
            @ToolParam(required = false, description = """
                    The starter to sit, by name or Sleeper player id. Leave
                    it out to sit the weakest starter whose slot accepts
                    the incoming player.""")
            String sit) {
        return switch (loader.load()) {
            case SourceResult.Unavailable<UserWeek> unavailable -> unavailable(unavailable);
            case SourceResult.Ok<UserWeek> ok ->
                planner.whatIf(ok.value(), clock.instant(), start, sit);
        };
    }

    private static <T, R> ToolAnswer<R> unavailable(SourceResult.Unavailable<T> unavailable) {
        return ToolAnswer.unavailable("%s: %s".formatted(
                unavailable.source(), unavailable.reason()));
    }
}
