package otto.ask;

import java.time.Clock;
import java.util.List;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import otto.advice.RankConvention;
import otto.advice.ToolAnswer;

/**
 * The chat tools for questions about players and NFL teams rather than
 * the user's lineup: news, ceilings, stats, team usage, league ranks and
 * stat leaders.
 */
@Component
public class PlayerTools {

    private final PlayerAnalysis players;
    private final PlayerCeiling ceilings;
    private final PlayerStatsAnswers playerStats;
    private final TeamUsage teamUsage;
    private final LeagueRankLookup leagueRanks;
    private final Clock clock;

    public PlayerTools(PlayerAnalysis players, PlayerCeiling ceilings,
            PlayerStatsAnswers playerStats, TeamUsage teamUsage, LeagueRankLookup leagueRanks,
            Clock clock) {
        this.players = players;
        this.ceilings = ceilings;
        this.playerStats = playerStats;
        this.teamUsage = teamUsage;
        this.leagueRanks = leagueRanks;
        this.clock = clock;
    }

    @LeagueNeed(LeagueNeed.When.NEVER)
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

    @LeagueNeed(LeagueNeed.When.ALWAYS)
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

    @LeagueNeed(LeagueNeed.When.FANTASY_POINTS_ASKED)
    @Tool(name = "get_player_stats", description = """
            One player's usage and advanced stats for one week, his team's
            last few games or the season so far: snaps and snap share,
            targets and target share, carries and carry share, air yards,
            air-yards share and WOPR, red-zone, end-zone and first-read
            targets, expected fantasy points against actual, his Next Gen
            Stats and, for a running back, PFR's contact figures, his
            fantasy points in his league's own scoring with his rank at
            his position when fantasyPoints is set, each with
            its sample and his season rank at his position, already written
            and fact-checked. Send its message word for word. Otto stores
            all of these, so call this before ever saying a stat is out of
            reach. Give at most one of week, weeksAgo and lastGames, and
            none for the season so far. Call it for "what was his snap %
            last week", "how many targets did he get", "how many points
            did he score last week", "what about the week before" and
            "what advanced stats can you see". Use it for player stats
            among one position, multiple positions or all skill players.""")
    public ToolAnswer<PlayerStatsAnswers.Answer> getPlayerStats(
            @ToolParam(description = "The player, by name or Sleeper player id")
            String player,
            @ToolParam(required = false, description = """
                    One NFL week by its number. Use it when he names a
                    week, or for the week before or after one you already
                    answered about.""")
            Integer week,
            @ToolParam(required = false, description = """
                    One week counted back: 1 for last week, 2 for the week
                    before last.""")
            Integer weeksAgo,
            @ToolParam(required = false, description = """
                    His team's last this many games, for "lately" or "the
                    last three weeks".""")
            Integer lastGames,
            @ToolParam(required = false, description = """
                    What he asked about, in his words, such as "snap
                    percentage" or "advanced stats".""")
            String asked,
            @ToolParam(required = false, description = """
                    true when he asks about fantasy points: what the
                    player scored, his points per game or where he ranks
                    in points. They come in his league's own scoring.
                    Leave it out for usage and advanced stats.""")
            Boolean fantasyPoints,
            @ToolParam(required = false, description = """
                    Usage rank comparison group explicitly requested by the user.
                    Use an array of QB, RB, WR and/or TE. For all skill positions
                    give all four. Omit when no group is named.""")
            List<String> rankPositions, ToolContext context) {
        CheckedAnswers checked = CheckedAnswers.of(context);
        return switch (StatsSpan.parse(week, weeksAgo, lastGames)) {
            case StatsSpan.Parsed.Rejected rejected -> ToolAnswer.unavailable(rejected.reason());
            case StatsSpan.Parsed.Ok parsed -> checked.offer(playerStats.answer(player,
                    parsed.span(), asked, Boolean.TRUE.equals(fantasyPoints), rankPositions,
                    clock.instant(), checked.delivery()), PlayerStatsAnswers.Answer::message);
        };
    }

    @LeagueNeed(LeagueNeed.When.NEVER)
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

    @LeagueNeed(LeagueNeed.When.RANKED_IN_FANTASY_POINTS)
    @Tool(name = "get_league_rank", description = """
            Where one player ranks among his position, one defense
            against a position, or one team's remaining schedule for a
            position, on one stat this season, with the top and bottom
            of the table. """ + RankConvention.DIRECTION + " "
            + RankConvention.TONE + """
             Quote ranks as written, such as 3rd or 5th from the bottom.
            For a player, quote his rank as "quoted" writes it, sample
            included, and name which position he ranks among. Keep a separate
            "askedOverall" comparison explicitly labeled as across skill positions.
            Use get_player_stats for one player's stats across an explicitly named
            group of multiple positions. Call this
            for "where does he
            rank in target share", "best pass rush in the league",
            "toughest run defense" or "easiest schedule the rest of the
            way".""")
    public ToolAnswer<LeagueRankLookup.Answer> getLeagueRank(
            @ToolParam(description = """
                    The stat: for a player, any stat get_stat_leaders
                    lists, such as target_share, air_yards or
                    yards_before_contact_per_carry; points_allowed, rushing_yards_allowed,
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

    @LeagueNeed(LeagueNeed.When.NEVER)
    @Tool(name = "get_stat_leaders", description = """
            The league leaders in one player stat, top 10 unless he names
            a number, at one position or across every position, over the
            season or a span of weeks, each with its sample, already
            written. Send its message word for word. Call this for "top
            10 WRs in air yards", "top 5 RBs in yards before contact" and
            "who leads all players in targets?".""")
    public ToolAnswer<StatLeaders.Answer> getStatLeaders(
            @ToolParam(description = """
                    The stat, such as air_yards, targets, target_share,
                    receiving_yards, yards_per_carry,
                    yards_before_contact, red_zone_targets or
                    first_read_targets.""")
            String stat,
            @ToolParam(required = false, description = """
                    QB, RB, WR or TE, or all for every position. Leave
                    it out for every position.""")
            String position,
            @ToolParam(required = false, description = "How many to list, 10 when he names none")
            Integer count,
            @ToolParam(required = false, description = """
                    The first week counted, when he names a span such as
                    "weeks 3 to 6" or "since week 5".""")
            Integer fromWeek,
            @ToolParam(required = false, description = "The last week counted, for a span")
            Integer toWeek, ToolContext context) {
        CheckedAnswers checked = CheckedAnswers.of(context);
        return checked.offer(leagueRanks.leaders(stat, position, count, fromWeek, toWeek),
                StatLeaders.Answer::message);
    }
}
