package otto.ask;

import java.time.Clock;
import java.util.List;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import otto.advice.ToolAnswer;
import otto.board.WaiverPreferences;
import otto.waivers.WaiverBoard;
import otto.waivers.WaiverQuery;
import otto.waivers.WaiverScorer;

/**
 * The chat tools for the user's own lineup and waiver claims: his roster,
 * the recommended lineup, a what-if change, a head-to-head call, the
 * waiver board and his waiver preference.
 */
@Component
public class LineupTools {

    private static final String WEEK_PARAM = """
            Which week the lineup is for: "next" for next week, or a week
            number such as "11". Leave it out for this week.""";

    private final UserWeekLoader loader;
    private final LineupPlanner planner;
    private final LineupAnswers lineups;
    private final PlayerAnalysis players;
    private final WaiverScorer waivers;
    private final WaiverPreferences waiverPreferences;
    private final Clock clock;

    public LineupTools(UserWeekLoader loader, LineupPlanner planner, LineupAnswers lineups,
            PlayerAnalysis players, WaiverScorer waivers, WaiverPreferences waiverPreferences,
            Clock clock) {
        this.loader = loader;
        this.planner = planner;
        this.lineups = lineups;
        this.players = players;
        this.waivers = waivers;
        this.waiverPreferences = waiverPreferences;
        this.clock = clock;
    }

    @LeagueNeed(LeagueNeed.When.ALWAYS)
    @Tool(name = "get_roster_status", description = """
            The user's current roster this week: every starting slot with
            who fills it, their projected points and what stops them
            scoring, plus bench, IR and taxi sections when the league
            supports them. Call this for
            any question about who is on the team or how they look.""")
    public ToolAnswer<LineupPlanner.RosterStatus> getRosterStatus() {
        return ToolAnswer.from(loader.load(),
                team -> ToolAnswer.of(planner.rosterStatus(team, clock.instant())));
    }

    @LeagueNeed(LeagueNeed.When.ALWAYS)
    @Tool(name = "recommend_lineup", description = """
            The recommended lineup for one week, already written and
            checked: it names the week, says which games are left and
            which starters have locked when the week is under way, gives
            the start-over-sit swaps or the reason to change nothing, and
            lists the lineup. Send its message word for word. Call this
            for "lineup", start/sit questions, and any request for the
            best lineup, this week's or a later one's.""")
    public ToolAnswer<LineupPlanner.LineupPlan> recommendLineup(
            @ToolParam(required = false, description = WEEK_PARAM) String week,
            ToolContext context) {
        return CheckedAnswers.of(context).offer(lineups.recommend(week, clock.instant()),
                LineupPlanner.LineupPlan::message);
    }

    @LeagueNeed(LeagueNeed.When.ALWAYS)
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
            String sit,
            @ToolParam(required = false, description = WEEK_PARAM) String week) {
        return lineups.whatIf(week, clock.instant(), start, sit);
    }

    @LeagueNeed(LeagueNeed.When.ALWAYS)
    @Tool(name = "compare_players", description = """
            Weighs two players against each other for this week:
            projections in league scoring, the matchup each faces from
            the defense-versus-position table, injury status, depth-chart
            role, season production and recent news, with the reasons
            behind the call. Either player may be on any roster or on
            none. Call this for "who do I start", "X or Y" and any
            head-to-head question.""")
    public ToolAnswer<PlayerAnalysis.Comparison> comparePlayers(
            @ToolParam(description = "The first player, by name or Sleeper player id")
            String first,
            @ToolParam(description = "The second player, by name or Sleeper player id")
            String second) {
        return ToolAnswer.from(loader.week(),
                week -> players.compare(week, clock.instant(), first, second));
    }

    @LeagueNeed(LeagueNeed.When.ALWAYS)
    @Tool(name = "rank_waiver_targets", description = """
            The best free agents in the league right now, ranked by a
            waiver score out of 100 built from projection above
            replacement, depth-chart usage, Sleeper's trending adds and
            recent news. Each one carries a role tag - stream, solid or
            breakout - and a suggested FAAB bid range from the budget
            the user has left, with the reasons behind both. It also
            answers the drop side of a pickup: name the rostered players
            the user would drop and every candidate carries what he is
            worth over each of them. Call this for waivers, "who should
            I pick up", "is anybody better than X", free agents, and any
            request for targets at a position.""")
    public ToolAnswer<WaiverBoard> rankWaiverTargets(
            @ToolParam(required = false, description = """
                    One position to rank: QB, RB, WR, TE, FLEX for the
                    flex positions, or all. Leave it out for all.""")
            String position,
            @ToolParam(required = false, description = """
                    How many candidates to return. Leave it out for
                    five.""")
            Integer count,
            @ToolParam(required = false, description = """
                    The players on the user's own roster he would drop,
                    by name or Sleeper player id. Every candidate then
                    carries the projected points he gains over each of
                    them. Use it for "who is better than X" and "who
                    would I drop". Leave it out when he named nobody.""")
            List<String> replacing,
            @ToolParam(required = false, description = """
                    True to keep the board to the positions where the
                    user has no bench player above replacement level.
                    Use it for "where do I need help". Leave it out
                    otherwise.""")
            Boolean needsOnly) {
        return switch (WaiverQuery.of(position, count, replacing, needsOnly)) {
            case WaiverQuery.Parsed.Rejected rejected -> ToolAnswer.unavailable(rejected.reason());
            case WaiverQuery.Parsed.Ok parsed -> ToolAnswer.from(loader.leagueWeek(),
                    league -> ToolAnswer.from(waivers.rank(league, clock.instant(),
                            parsed.query()), ToolAnswer::of));
        };
    }

    @LeagueNeed(LeagueNeed.When.ALWAYS)
    @Tool(name = "manage_waiver_preferences", description = """
            The user's preference for his next waiver board, the one Otto
            builds before the league's next claim deadline. A preference
            covers that one claim round and ends when its claims close.
            Call this to set one when he asks to steer the board or the
            waiver round, such as "focus on TE this week" or "only TEs on
            my board", to show what is in force, or to clear it. A one-off
            "only show me TEs" about the answer he is reading is not a
            preference: answer it with rank_waiver_targets and its
            position. Only this tool knows what is in force: never treat a
            preference mentioned earlier in the conversation as active,
            and never set one he did not ask for in his latest message.""")
    public ToolAnswer<WaiverPreferences.View> manageWaiverPreferences(
            @ToolParam(description = "set, show or clear")
            String action,
            @ToolParam(required = false, description = """
                    The positions to set: any of QB, RB, WR and TE. Needed
                    to set; leave it out to show or clear.""")
            List<String> positions,
            @ToolParam(required = false, description = """
                    True when he wants the board to show only those
                    positions. Leave it out for a focus, which ranks them
                    first and still shows a clearly better pickup
                    elsewhere.""")
            Boolean only) {
        return ToolAnswer.from(loader.league(), league -> waiverPreferences.apply(league,
                action, positions, Boolean.TRUE.equals(only), clock.instant()));
    }
}
