package otto.ask;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.check.WeekFacts;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;

/**
 * The tools the Lane A agent loop routes questions to. Each one runs
 * deterministic Java and hands back computed facts; the model chooses
 * which to call and writes the words, never the numbers.
 *
 * This is nine of the twelve tools the spec names. Keep them coarse:
 * one tool per question a user would ask, not one per field.
 */
@Component
public class AskTools {

    private final UserWeekLoader loader;
    private final LineupPlanner planner;
    private final PlayerAnalysis players;
    private final LeagueAnalysis league;
    private final Clock clock;
    private final double edgeThreshold;

    public AskTools(UserWeekLoader loader, LineupPlanner planner, PlayerAnalysis players,
            LeagueAnalysis league, Clock clock, OttoProperties properties) {
        this.loader = loader;
        this.planner = planner;
        this.players = players;
        this.league = league;
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
        return switch (loader.week()) {
            case SourceResult.Unavailable<WeekFacts> unavailable -> unavailable(unavailable);
            case SourceResult.Ok<WeekFacts> ok ->
                players.compare(ok.value(), clock.instant(), first, second);
        };
    }

    @Tool(name = "get_player_news", description = """
            The latest news on one player, newest first, with the
            headline, the report and the analysis behind it. This one
            always reads live, so call it for anything about what has
            just happened to a player.""")
    public ToolAnswer<PlayerAnalysis.PlayerNews> getPlayerNews(
            @ToolParam(description = "The player, by name or Sleeper player id")
            String player) {
        return players.news(player, clock.instant());
    }

    @Tool(name = "get_standings", description = """
            The league table: every team with its record, points for and
            against, in seed order, and where the user sits. Call this
            for standings, seeding and "how am I doing" questions.""")
    public ToolAnswer<LeagueAnalysis.Standings> getStandings() {
        return switch (loader.leagueWeek()) {
            case SourceResult.Unavailable<LeagueWeek> unavailable -> unavailable(unavailable);
            case SourceResult.Ok<LeagueWeek> ok -> ToolAnswer.of(league.standings(ok.value()));
        };
    }

    @Tool(name = "analyze_playoff_race", description = """
            What each team still needs: who has clinched a playoff place,
            who is out, and how many of his remaining games the user must
            win to be certain of a seed. Call this for playoff, clinch,
            elimination and "what do I need" questions.""")
    public ToolAnswer<LeagueAnalysis.PlayoffRace> analyzePlayoffRace() {
        return switch (loader.leagueWeek()) {
            case SourceResult.Unavailable<LeagueWeek> unavailable -> unavailable(unavailable);
            case SourceResult.Ok<LeagueWeek> ok -> league.playoffRace(ok.value());
        };
    }

    @Tool(name = "get_team_roster", description = """
            Any league mate's team: the lineup they have set with
            projections, their bench, and where they are deep or short
            against replacement level at each position. Call this to size
            up a trade partner or to answer "what does X have".""")
    public ToolAnswer<LeagueAnalysis.TeamRoster> getTeamRoster(
            @ToolParam(description = "The manager, by the name they use in the league")
            String manager) {
        return switch (loader.leagueWeek()) {
            case SourceResult.Unavailable<LeagueWeek> unavailable -> unavailable(unavailable);
            case SourceResult.Ok<LeagueWeek> ok -> {
                List<RosterSnapshot> matches = ok.value().managersMatching(manager);
                if (matches.size() != 1) {
                    yield ToolAnswer.unavailable(noManager(ok.value(), manager, matches));
                }
                yield league.teamRoster(ok.value(), matches.getFirst(), clock.instant());
            }
        };
    }

    private static String noManager(LeagueWeek league, String reference,
            List<RosterSnapshot> matches) {
        if (matches.isEmpty()) {
            return "no manager in this league matches \"%s\"; the teams are %s".formatted(
                    reference, league.rosters().stream().map(RosterSnapshot::manager).toList());
        }
        return "\"%s\" matches more than one manager: %s".formatted(reference,
                matches.stream().map(RosterSnapshot::manager).toList());
    }

    private static <T, R> ToolAnswer<R> unavailable(SourceResult.Unavailable<T> unavailable) {
        return ToolAnswer.unavailable("%s: %s".formatted(
                unavailable.source(), unavailable.reason()));
    }
}
