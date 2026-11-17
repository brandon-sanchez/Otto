package otto.habits;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import otto.advice.AdviceAnswer;
import otto.advice.AdviceCheck;
import otto.advice.AdviceForm;
import otto.advice.AdvicePrompt;
import otto.advice.EvidenceBundle;
import otto.advice.FriendVoice;

/**
 * The habits answer as a form of advice: the instructions it is written
 * under, and its checks. It must pass every {@link AdviceCheck} rule and
 * read as sentences, never as a list.
 */
final class HabitForm implements AdviceForm<AdviceAnswer> {

    static final String INSTRUCTIONS = AdvicePrompt.preamble(
            "about his habits as a manager, or how he can improve",
            AdvicePrompt.Naming.LISTED_BY_FULL_NAME) + """

            Each "habit-" item is a habit his own results show cost him points or FAAB
            dollars. Lead with what the costliest one cost him, then the pattern behind
            it in plain words, then at most one fix, taken from its "fix" and offered as
            something to try rather than an order. Bring in a second habit the same way
            only when it fits. Never mention a habit the evidence does not list, and never lecture.
            The "activity" item sums up his waiver, FAAB and trade moves: use it only
            when it helps explain a habit.

            Write one or two short paragraphs with no lists and no headings. Never write a
            confidence label such as High or Medium. Stay under "maxLength" characters.
            """ + FriendVoice.RULE
            + AdvicePrompt.answerShape(AdvicePrompt.Naming.LISTED_BY_FULL_NAME);

    /** A line that opens like a list item: a dash, a bullet or a number. */
    private static final Pattern LIST_LINE =
            Pattern.compile("(?m)^\\s*(?:[-*\\u2022]|\\d+[.)])\\s");

    private final AdviceCheck check;

    HabitForm(AdviceCheck check) {
        this.check = check;
    }

    @Override
    public String instructions() {
        return INSTRUCTIONS;
    }

    @Override
    public AdviceCheck.Verdict<AdviceAnswer> check(EvidenceBundle bundle, String reply) {
        AdviceCheck.Verdict<AdviceAnswer> verdict = check.check(bundle, reply);
        Optional<AdviceAnswer> answer = switch (verdict) {
            case AdviceCheck.Verdict.Passed<AdviceAnswer> passed -> Optional.of(passed.answer());
            case AdviceCheck.Verdict.Failed<AdviceAnswer> failed -> failed.answer();
        };
        if (answer.isEmpty()) {
            return verdict;
        }
        List<String> failures = new ArrayList<>(verdict.failures());
        if (LIST_LINE.matcher(answer.get().message()).find()) {
            failures.add("Write natural sentences, not a list.");
        }
        return AdviceCheck.Verdict.checked(answer.get(), failures);
    }
}
