package otto.ask;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import otto.check.WeekFacts;
import otto.settings.SettingsService;
import otto.settings.SettingsStore;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;
import otto.waivers.WaiverBoard;
import otto.waivers.WaiverQuery;
import otto.waivers.WaiverScorer;
import otto.watchlist.WatchlistService;

/**
 * The tools the Lane A agent loop routes questions to. Each one runs
 * deterministic Java and hands back computed facts; the model chooses
 * which to call and writes the words, never the numbers.
 *
 * This is eleven of the twelve tools the spec names, plus the Settings
 * tool the chat needs to change them. Keep them coarse:
 * one tool per question a user would ask, not one per field.
 */
@Component
public class AskTools {

    private final UserWeekLoader loader;
    private final LineupPlanner planner;
    private final PlayerAnalysis players;
    private final LeagueAnalysis league;
    private final WaiverScorer waivers;
    private final WatchlistService watchlist;
    private final SettingsService settings;
    private final SettingsStore settingsStore;
    private final Clock clock;

    public AskTools(UserWeekLoader loader, LineupPlanner planner, PlayerAnalysis players,
            LeagueAnalysis league, WaiverScorer waivers, WatchlistService watchlist,
            SettingsService settings, SettingsStore settingsStore, Clock clock) {
        this.loader = loader;
        this.planner = planner;
        this.players = players;
        this.league = league;
        this.waivers = waivers;
        this.watchlist = watchlist;
        this.settings = settings;
        this.settingsStore = settingsStore;
        this.clock = clock;
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
                    String.format(Locale.ROOT, "%.1f", settingsStore.edgeThreshold())));
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
            case WaiverQuery.Parsed.Ok parsed -> board(parsed.query());
        };
    }

    private ToolAnswer<WaiverBoard> board(WaiverQuery query) {
        return switch (loader.leagueWeek()) {
            case SourceResult.Unavailable<LeagueWeek> unavailable -> unavailable(unavailable);
            case SourceResult.Ok<LeagueWeek> ok -> ranked(ok.value(), query);
        };
    }

    private ToolAnswer<WaiverBoard> ranked(LeagueWeek leagueWeek, WaiverQuery query) {
        return switch (waivers.rank(leagueWeek, clock.instant(), query)) {
            case SourceResult.Unavailable<WaiverBoard> unavailable -> unavailable(unavailable);
            case SourceResult.Ok<WaiverBoard> board -> ToolAnswer.of(board.value());
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

    @Tool(name = "manage_watchlist", description = """
            The user's watchlist: the players he wants watched wherever
            they are, rostered or not. Watched players alert him when
            somebody drops them, when another manager claims them, when
            Sleeper's add counts spike, and when their projection moves.
            Call this to add a player, to remove one, or to list what is
            watched. Sleeper has no watchlist of its own, so this list is
            the only one there is.""")
    public ToolAnswer<WatchlistService.View> manageWatchlist(
            @ToolParam(description = "add, remove or list")
            String action,
            @ToolParam(required = false, description = """
                    The player, by name or Sleeper player id. Needed to
                    add or remove; leave it out to list.""")
            String player) {
        return watchlist.apply(action, player, clock.instant());
    }

    @Tool(name = "manage_settings", description = """
            The assistant's own settings and the mute list: which alert
            triggers are on, how many projected points make an edge worth
            a message, where the Notable Player cutoffs sit, and what is
            currently silenced. Call this to show them, to change one, or
            to mute or unmute a class of alerts or one player's news.
            There is no settings screen, so this tool is the only way.""")
    public ToolAnswer<SettingsService.View> manageSettings(
            @ToolParam(description = "show, set, mute or unmute")
            String action,
            @ToolParam(required = false, description = """
                    What to act on. To set: a setting name -
                    status_transition, lineup_legality, bench_edge,
                    watchlist, waiver, league_activity, edge_threshold,
                    notable_qb, notable_rb, notable_wr or notable_te. To
                    mute or unmute: one of those trigger names, or a
                    player by name or Sleeper player id.""")
            String name,
            @ToolParam(required = false, description = """
                    The new value when setting: on or off for a trigger, a
                    number of points for the edge threshold, a rank for a
                    Notable Player cutoff.""")
            String value) {
        return settings.apply(action, name, value, clock.instant());
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
