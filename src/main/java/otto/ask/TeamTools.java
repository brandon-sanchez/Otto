package otto.ask;

import java.time.Clock;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import otto.advice.ToolAnswer;
import otto.habits.HabitService;

/**
 * The chat tools for teams and the league table: standings, the playoff
 * race, a league mate's roster, the user's habits and weaknesses, and
 * every team's direction.
 */
@Component
public class TeamTools {

    private final UserWeekLoader loader;
    private final LeagueAnalysis league;
    private final HabitService habits;
    private final TeamWeaknesses weaknesses;
    private final TeamDirectionAnswers directions;
    private final Clock clock;

    public TeamTools(UserWeekLoader loader, LeagueAnalysis league, HabitService habits,
            TeamWeaknesses weaknesses, TeamDirectionAnswers directions, Clock clock) {
        this.loader = loader;
        this.league = league;
        this.habits = habits;
        this.weaknesses = weaknesses;
        this.directions = directions;
        this.clock = clock;
    }

    @LeagueNeed(LeagueNeed.When.ALWAYS)
    @Tool(name = "get_standings", description = """
            The league table: every team with its record, points for and
            against, in seed order, and where the user sits. Call this
            for standings, seeding and "how am I doing" questions.""")
    public ToolAnswer<LeagueAnalysis.Standings> getStandings() {
        return ToolAnswer.from(loader.leagueWeek(),
                week -> ToolAnswer.of(league.standings(week)));
    }

    @LeagueNeed(LeagueNeed.When.ALWAYS)
    @Tool(name = "analyze_playoff_race", description = """
            What each team still needs: who has clinched a playoff place,
            who is out, and how many of his remaining games the user must
            win to be certain of a seed. Call this for playoff, clinch,
            elimination and "what do I need" questions.""")
    public ToolAnswer<LeagueAnalysis.PlayoffRace> analyzePlayoffRace() {
        return ToolAnswer.from(loader.leagueWeek(), league::playoffRace);
    }

    @LeagueNeed(LeagueNeed.When.ALWAYS)
    @Tool(name = "get_team_roster", description = """
            Any league mate's team: the lineup they have set with
            projections, their bench, IR and taxi sections, and where they are deep or short
            against replacement level at each position. Call this to size
            up a trade partner or to answer "what does X have".""")
    public ToolAnswer<LeagueAnalysis.TeamRoster> getTeamRoster(
            @ToolParam(description = "The manager, by the name they use in the league")
            String manager) {
        return ToolAnswer.from(loader.leagueWeek(), week -> Managers.named(week, manager,
                roster -> league.teamRoster(week, roster, clock.instant())));
    }

    @LeagueNeed(LeagueNeed.When.ALWAYS)
    @Tool(name = "review_habits", description = """
            How the user manages his team, measured from his own results
            this season: points left on his bench, how following or
            overriding Otto's start/sit and waiver calls turned out,
            chasing last week's big game, starting Questionable players,
            favouring one NFL team, and his waiver, FAAB and trade
            activity. It reports only the habits that cost him points.
            Call this only when he asks about himself as a manager, for
            "what are my habits", "what mistakes am I making", "how can
            I improve?" and "how do I get better at this", and never
            bring habits up otherwise. Its message is already written and
            fact-checked: send it exactly as it is, adding nothing.""")
    public ToolAnswer<HabitService.Answer.Written> reviewHabits(ToolContext context) {
        return CheckedAnswers.of(context).offer(habitsAnswer(),
                HabitService.Answer.Written::message);
    }

    @LeagueNeed(LeagueNeed.When.ALWAYS)
    @Tool(name = "get_team_weaknesses", description = """
            Where the user's team is strong, thin or in surplus, position
            by position against every team in his league, bench and depth
            included, with his team's direction, how high its big weeks
            reach and up to three waiver or trade moves, already written
            and fact-checked. Send
            its message word for word. Call this only when he asks about
            his team or roster, for "what are my team's weaknesses", "how
            can I improve my team" and "where is my roster thin". "How
            can I improve?" with no team or roster named asks about him
            as a manager: call review_habits for it instead.""")
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

    @LeagueNeed(LeagueNeed.When.ALWAYS)
    @Tool(name = "get_team_directions", description = """
            Every team in the user's league labelled contending, in the
            middle or rebuilding, his own included, each with a one-line
            reason from its market value, record and points, already
            written and fact-checked. Send its message word for word.
            Call this for "who's contending in my league?", "who is
            rebuilding?" and "who's buying and who's selling?".""")
    public ToolAnswer<TeamDirectionAnswers.Answer> getTeamDirections(ToolContext context) {
        CheckedAnswers checked = CheckedAnswers.of(context);
        return checked.offer(directions.answer(clock.instant(), checked.delivery()),
                TeamDirectionAnswers.Answer::message);
    }

    @LeagueNeed(LeagueNeed.When.ALWAYS)
    @Tool(name = "manage_team_direction", description = """
            The direction the user gives his own team: contending, middle
            or rebuilding. Otto uses his choice in place of the label it
            measures until he changes or clears it. In a dynasty league
            Otto never treats him as rebuilding until he says so. Call
            this with set when he says which way his team is headed,
            such as "we're rebuilding" or "yes, I'm rebuilding", and
            with clear when he asks Otto to judge it again itself.""")
    public ToolAnswer<TeamDirectionAnswers.Changed> manageTeamDirection(
            @ToolParam(description = "set or clear")
            String action,
            @ToolParam(required = false, description = """
                    contending, middle or rebuilding. Needed to set.""")
            String direction) {
        return ToolAnswer.from(loader.league(),
                league -> directions.change(league, action, direction));
    }

    private ToolAnswer<HabitService.Answer.Written> habitsAnswer() {
        return ToolAnswer.from(loader.leagueWeek(), week -> switch (habits.answer(
                week.league(), week.snapshot(), week.week(), clock.instant())) {
            case HabitService.Answer.Written written -> ToolAnswer.of(written);
            case HabitService.Answer.Unavailable none -> ToolAnswer.unavailable(none.reason());
        });
    }
}
