package otto.ask;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import otto.advice.AdviceAnswer;
import otto.advice.AdviceCheck;
import otto.advice.AdviceForm;
import otto.advice.AdvicePrompt;
import otto.advice.AnswerDelivery;
import otto.advice.CheckedAdvice;
import otto.advice.EvidenceBundle;
import otto.advice.FriendVoice;
import otto.advice.RankConvention;
import otto.advice.ToolAnswer;
import otto.league.LeagueWeek;
import otto.records.RunKind;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;
import otto.team.Direction;
import otto.team.DirectionChoices;
import otto.team.LeagueDirections;
import otto.team.TeamDirections;

/**
 * Answers "who's contending in my league?" in chat, and lets the user
 * set or clear his own team's direction. Code labels every team; the
 * model explains the labels in one message, and {@link AdviceCheck}
 * holds every number and rank in it to the evidence first.
 */
@Component
public class TeamDirectionAnswers {

    private static final int MAX_LENGTH = 1800;

    private static final String RUN_ID_PREFIX = "team-directions-";

    private static final String INSTRUCTIONS = AdvicePrompt.preamble(
            "about the teams in his league", AdvicePrompt.Naming.NO_PLAYER)
            + RankConvention.ADVICE_RULES + FriendVoice.RULE + """

            Write plain text. Say which teams are contending, which are in the middle and
            which are rebuilding, using each "team-" item's "direction", and give each team
            its reason in a short clause drawn from its "reason". The item whose manager is
            "you" is the user's own team: call it "your team". When "basis" says market
            values could not be read, say so in one sentence. When the user's item has a
            "question", end the message by asking it. Stay under "maxLength" characters.
            """ + AdvicePrompt.answerShape(AdvicePrompt.Naming.NO_PLAYER);

    private static final CheckedAdvice.Fallbacks FALLBACKS = new CheckedAdvice.Fallbacks(
            "I cannot reach my language model to explain the teams' directions right now",
            "I worked out where every team is headed, but I could not check my explanation "
                    + "against the numbers, so I am holding it back rather than risk a wrong "
                    + "figure");

    private final UserWeekLoader loader;
    private final TeamDirections directions;
    private final DirectionChoices choices;
    private final CheckedAdvice advice;
    private final AdviceForm<AdviceAnswer> form;

    public TeamDirectionAnswers(UserWeekLoader loader, TeamDirections directions,
            DirectionChoices choices, CheckedAdvice advice, AdviceCheck check) {
        this.loader = loader;
        this.directions = directions;
        this.choices = choices;
        this.advice = advice;
        this.form = AdviceForm.of(INSTRUCTIONS, check::check);
    }

    /**
     * The checked read of every team's direction.
     *
     * @param message the checked text, which the user receives word for
     *        word unless the chat model gathered other tool results for
     *        the same question
     */
    public record Answer(String league, String message) {
    }

    /**
     * What a change to the user's own direction did.
     *
     * @param outcome what the change did, in a sentence
     */
    public record Changed(String outcome) {
    }

    /**
     * Labels every team in the league and has the model explain it.
     *
     * <p>Side effects: the market value reads, one or two model calls,
     * one Decision Record of kind {@link RunKind#TEAM_DIRECTIONS}
     * whatever the outcome, and, once an answer that asks whether the
     * user is rebuilding reaches him, a note that the question was asked.
     *
     * @param delivery how a checked message reaches the user, which the
     *        Decision Record keeps as its outcome
     * @return the checked message, or why there is none in plain words
     */
    public ToolAnswer<Answer> answer(Instant now, AnswerDelivery delivery) {
        return switch (loader.leagueWeek()) {
            case SourceResult.Unavailable<LeagueWeek> unavailable -> ToolAnswer.unavailable(
                    "%s: %s".formatted(unavailable.source(), unavailable.reason()));
            case SourceResult.Ok<LeagueWeek> ok -> switch (directions.read(ok.value(), now)) {
                case SourceResult.Unavailable<LeagueDirections> none -> ToolAnswer.unavailable(
                        "no game has been played yet and I couldn't read market values, so "
                                + "I have nothing to judge the teams by: " + none.reason());
                case SourceResult.Ok<LeagueDirections> read ->
                    explain(ok.value(), read.value(), now, delivery);
            };
        };
    }

    /**
     * Sets or clears the direction the user gives his own team.
     *
     * <p>Side effects: writes the stored choice.
     *
     * @param action set or clear
     * @param direction contending, middle or rebuilding; read only by set
     * @return unavailable for an unknown action or direction
     */
    public ToolAnswer<Changed> change(SleeperAdapter.League league, String action,
            String direction) {
        String verb = action == null ? "" : action.strip().toLowerCase(Locale.ROOT);
        return switch (verb) {
            case "set" -> Direction.named(direction)
                    .map(named -> {
                        choices.set(league.leagueId(), named);
                        return ToolAnswer.of(new Changed(("Got it, I'll treat your team as %s "
                                + "until you tell me otherwise.").formatted(named.words())));
                    })
                    .orElseGet(() -> ToolAnswer.unavailable(("\"%s\" isn't a direction I use; "
                            + "say contending, middle or rebuilding").formatted(direction)));
            case "clear" -> {
                choices.clear(league.leagueId());
                yield ToolAnswer.of(new Changed("Done, I'll go back to judging your team's "
                        + "direction from its record, points and market value."));
            }
            default -> ToolAnswer.unavailable(
                    "unknown action \"%s\"; use set or clear".formatted(action));
        };
    }

    private ToolAnswer<Answer> explain(LeagueWeek leagueWeek, LeagueDirections read,
            Instant now, AnswerDelivery delivery) {
        SleeperAdapter.League league = leagueWeek.league();
        EvidenceBundle bundle = TeamDirectionEvidence.bundle(league.name(), read, MAX_LENGTH,
                now);
        CheckedAdvice.Checked checked = advice.write(bundle, form,
                new CheckedAdvice.Run(RUN_ID_PREFIX, List.of(league.leagueId()),
                        RunKind.TEAM_DIRECTIONS, league, List.of(), "explanation"),
                now, delivery);
        if (checked.reachesUser()) {
            read.user().ifPresent(user -> directions.asked(leagueWeek, user));
        }
        return checked.answer(message -> new Answer(league.name(), message), FALLBACKS);
    }
}
