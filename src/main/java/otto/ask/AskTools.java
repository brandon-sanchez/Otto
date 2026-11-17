package otto.ask;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import otto.advice.RankConvention;
import otto.board.WaiverPreferences;
import otto.check.WeekFacts;
import otto.habits.HabitService;
import otto.settings.SettingsService;
import otto.settings.SettingsStore;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;
import otto.trade.TradeEvaluator;
import otto.waivers.WaiverBoard;
import otto.waivers.WaiverQuery;
import otto.waivers.WaiverScorer;
import otto.watchlist.WatchlistService;

/**
 * The tools the Lane A agent loop routes questions to. Each one runs
 * deterministic Java and hands back computed facts; the model chooses
 * which to call and writes the words, never the numbers.
 */
@Component
public class AskTools {

    private final UserWeekLoader loader;
    private final LineupPlanner planner;
    private final PlayerAnalysis players;
    private final LeagueAnalysis league;
    private final WaiverScorer waivers;
    private final TradeEvaluator trades;
    private final WatchlistService watchlist;
    private final SettingsService settings;
    private final SettingsStore settingsStore;
    private final TeamUsage teamUsage;
    private final LeagueRankLookup leagueRanks;
    private final WaiverPreferences waiverPreferences;
    private final PlayerCeiling ceilings;
    private final HabitService habits;
    private final MarketValueAnswers marketValues;
    private final TeamWeaknesses weaknesses;
    private final Clock clock;

    public AskTools(UserWeekLoader loader, LineupPlanner planner, PlayerAnalysis players,
            LeagueAnalysis league, WaiverScorer waivers, TradeEvaluator trades,
            WatchlistService watchlist, SettingsService settings, SettingsStore settingsStore,
            TeamUsage teamUsage, LeagueRankLookup leagueRanks,
            WaiverPreferences waiverPreferences, PlayerCeiling ceilings, HabitService habits,
            MarketValueAnswers marketValues, TeamWeaknesses weaknesses, Clock clock) {
        this.loader = loader;
        this.planner = planner;
        this.players = players;
        this.league = league;
        this.waivers = waivers;
        this.trades = trades;
        this.watchlist = watchlist;
        this.settings = settings;
        this.settingsStore = settingsStore;
        this.teamUsage = teamUsage;
        this.leagueRanks = leagueRanks;
        this.waiverPreferences = waiverPreferences;
        this.ceilings = ceilings;
        this.habits = habits;
        this.marketValues = marketValues;
        this.weaknesses = weaknesses;
        this.clock = clock;
    }

    /** The league's rules, as the projection and lineup math applies them. */
    public record LeagueSettings(String league, String status, List<String> rosterPositions,
            Map<String, Double> scoring, String edgeThreshold) {
    }

    @Tool(name = "get_roster_status", description = """
            The user's current roster this week: every starting slot with
            who fills it, their projected points and what stops them
            scoring, plus bench, IR and taxi sections when the league
            supports them. Call this for
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
        return switch (loader.league()) {
            case SourceResult.Unavailable<SleeperAdapter.League> unavailable ->
                unavailable(unavailable);
            case SourceResult.Ok<SleeperAdapter.League> ok -> waiverPreferences.apply(ok.value(),
                    action, positions, Boolean.TRUE.equals(only), clock.instant());
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

    @Tool(name = "get_player_ceiling", description = """
            How high one player can score in a big week and how low a bad
            week usually goes: his chance of a boom week, the range most
            of his weeks land in, and his past boom weeks as history,
            already written and checked. Send its message word for word.
            Call this for "what's his ceiling", "is he boom or bust",
            "what's his floor" and "how high can he go".""")
    public ToolAnswer<PlayerCeiling.Answer> getPlayerCeiling(
            @ToolParam(description = "The player, by name or Sleeper player id")
            String player, ToolContext context) {
        CheckedAnswers checked = CheckedAnswers.of(context);
        return checked.offer(ceilings.explain(player, clock.instant(), checked.delivery()),
                PlayerCeiling.Answer::message);
    }

    @Tool(name = "get_team_usage", description = """
            One NFL team's usage by position group over its last four
            finished games, week by week, from nflverse: each player's
            snaps and snap share, targets and target share, carries and
            carry share, and the group's share of the team's targets and
            carries. Its table is written to fit one message; show it as
            it is. Call this for "Chargers RB usage", "Lions targets by
            position", backfield splits and target shares.""")
    public ToolAnswer<TeamUsage.Report> getTeamUsage(
            @ToolParam(description = """
                    The NFL team as the user wrote it: a nickname, a city
                    or a code, such as Chargers, LA Chargers or LAC.""")
            String team,
            @ToolParam(required = false, description = """
                    QB, RB, WR or TE. Leave it out to compare every
                    group's share of the team's targets and carries.""")
            String position, ToolContext context) {
        return CheckedAnswers.of(context).offer(teamUsage.forTeam(team, position),
                TeamUsage.Report::table);
    }

    @Tool(name = "get_league_rank", description = """
            Where one player ranks among his position, one defense
            against a position, or one team's remaining schedule for a
            position, on one stat this season, with the top and bottom
            of the table. """ + RankConvention.DIRECTION + " "
            + RankConvention.TONE + """
             Quote ranks as written, such as 3rd or 5th from the bottom.
            Call this for "where does he
            rank in target share", "best pass rush in the league",
            "toughest run defense" or "easiest schedule the rest of the
            way".""")
    public ToolAnswer<LeagueRankLookup.Answer> getLeagueRank(
            @ToolParam(description = """
                    The stat: targets_per_game, target_share,
                    carries_per_game, carry_share, snap_share,
                    yards_before_contact_per_carry or pressured_rate for a
                    player; points_allowed, rushing_yards_allowed,
                    yards_before_contact_allowed, pressure_rate or
                    blitz_rate for a defense; remaining_schedule for a
                    team's rest of season.""")
            String stat,
            @ToolParam(description = """
                    QB, RB, WR or TE: the players' position, or the
                    position a defense is ranked against.""")
            String position,
            @ToolParam(required = false, description = """
                    The player's full name, or the team for a defense
                    or schedule stat, such as Lions or DET. Leave it out for the
                    table alone.""")
            String who) {
        return leagueRanks.lookup(stat, position, who);
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
            projections, their bench, IR and taxi sections, and where they are deep or short
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

    @Tool(name = "evaluate_trade", description = """
            Prices a proposed trade with one league mate two ways: what
            the players do for each team's roster over the rest of the
            season in league scoring, and what both sides are worth at
            market value in this league's format, draft picks included.
            It gives a verdict, a confidence level, which of the two
            measures decides the call and why, and a leverage note saying
            what the partner is short of. Both teams can come out ahead,
            and the verdict is always the user's own. If tradeDeadline is
            present, start the answer with it. Then say which measure
            decides and describe the valuation. FAAB in a trade is
            ignored. Call this for "should I trade X for Y",
            "is this trade fair", and any trade offer the user wants
            judged.""")
    public ToolAnswer<TradeEvaluator.TradeEvaluation> evaluateTrade(
            @ToolParam(description = "The trade partner, by the name they use in the league")
            String manager,
            @ToolParam(description = """
                    What the user would receive: player names, draft
                    picks or FAAB, separated by commas.""")
            String gets,
            @ToolParam(description = """
                    What the user would send: player names, draft picks
                    or FAAB, separated by commas.""")
            String gives) {
        return switch (loader.leagueWeek()) {
            case SourceResult.Unavailable<LeagueWeek> unavailable -> unavailable(unavailable);
            case SourceResult.Ok<LeagueWeek> ok -> {
                List<RosterSnapshot> matches = ok.value().managersMatching(manager);
                if (matches.size() != 1) {
                    yield ToolAnswer.unavailable(noManager(ok.value(), manager, matches));
                }
                yield trades.evaluate(ok.value(), matches.getFirst(), gets, gives);
            }
        };
    }

    @Tool(name = "review_habits", description = """
            How the user manages his team, measured from his own results
            this season: points left on his bench, how following or
            overriding Otto's start/sit and waiver calls turned out,
            chasing last week's big game, starting Questionable players,
            favouring one NFL team, and his waiver, FAAB and trade
            activity. It reports only the habits that cost him points.
            Call this only when he asks about his habits, his mistakes
            or how he can improve as a manager, and never bring habits up
            otherwise. Its message is already written and fact-checked:
            send it exactly as it is, adding nothing.""")
    public ToolAnswer<HabitService.Answer.Written> reviewHabits(ToolContext context) {
        return CheckedAnswers.of(context).offer(habitsAnswer(),
                HabitService.Answer.Written::message);
    }

    private ToolAnswer<HabitService.Answer.Written> habitsAnswer() {
        return switch (loader.leagueWeek()) {
            case SourceResult.Unavailable<LeagueWeek> unavailable -> unavailable(unavailable);
            case SourceResult.Ok<LeagueWeek> ok -> switch (habits.answer(ok.value().league(),
                    ok.value().snapshot(), ok.value().week(), clock.instant())) {
                case HabitService.Answer.Written written -> ToolAnswer.of(written);
                case HabitService.Answer.Unavailable none -> ToolAnswer.unavailable(none.reason());
            };
        };
    }

    @Tool(name = "get_market_value", description = """
            What one player or future draft pick is worth on the trade
            market in this league's format (redraft or dynasty, 1QB or
            Superflex, team count and PPR): his market value, where he
            ranks and his tier, and how his value has moved over the last
            30 days, already written up and fact-checked. Send its message
            to the user exactly as written, adding nothing. Call this for
            "what's X worth", "what's his trade value", "has his value
            dropped" and the value of a pick such as a 2027 1st.""")
    public ToolAnswer<MarketValueAnswers.Reply> getMarketValue(
            @ToolParam(description = """
                    The player, by name or Sleeper player id, or a future
                    draft pick written with its year and round, such as
                    2027 1st or 2027 early 2nd.""")
            String subject, ToolContext context) {
        return CheckedAnswers.of(context).offer(marketValues.answer(subject),
                MarketValueAnswers.Reply::message);
    }

    @Tool(name = "get_team_weaknesses", description = """
            Where the user's team is strong, thin or in surplus, position
            by position against every team in his league, bench and depth
            included, with how high its big weeks reach and up to three
            waiver or trade moves, already written and fact-checked. Send
            its message word for word. Call this only when he asks, for
            "what are my team's weaknesses", "how can I improve my team"
            and "where is my roster thin".""")
    public ToolAnswer<TeamWeaknesses.Answer> getTeamWeaknesses(
            @ToolParam(required = false, description = """
                    The league he named, or his words such as "all of
                    them" when he asks about every league. Leave it out
                    when he named none.""")
            String league, ToolContext context) {
        CheckedAnswers checked = CheckedAnswers.of(context);
        return checked.offer(weaknesses.answer(league, clock.instant(), checked.delivery()),
                TeamWeaknesses.Answer::message);
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
            a message, where the Notable Player cutoffs sit, the most the
            user will bid on one waiver claim, and what is currently
            silenced. Call this to show them, to change one, or
            to mute or unmute a class of alerts or one player's news.
            There is no settings screen, so this tool is the only way.""")
    public ToolAnswer<SettingsService.View> manageSettings(
            @ToolParam(description = "show, set, mute or unmute")
            String action,
            @ToolParam(required = false, description = """
                    What to act on. To set: a setting name -
                    status_transition, lineup_legality, bench_edge,
                    watchlist, waiver, league_activity, edge_threshold,
                    notable_qb, notable_rb, notable_wr, notable_te or
                    max_bid. To
                    mute or unmute: one of those trigger names, or a
                    player by name or Sleeper player id.""")
            String name,
            @ToolParam(required = false, description = """
                    The new value when setting: on or off for a trigger, a
                    number of points for the edge threshold, a rank for a
                    Notable Player cutoff, whole dollars or none for
                    max_bid.""")
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
