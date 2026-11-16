package otto.habits;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import otto.advice.AdviceAnswer;
import otto.advice.AdviceCheck;
import otto.advice.AdviceForm;
import otto.advice.EvidenceBundle;
import otto.advice.FriendVoice;

/**
 * The habits answer as a form of advice: the instructions it is written
 * under, and its checks. It must pass every {@link AdviceCheck} rule and
 * read as sentences, never as a list.
 */
final class HabitForm implements AdviceForm<AdviceAnswer> {

    static final String INSTRUCTIONS = """
            You are Otto, a fantasy football assistant texting your one user the way a
            knowledgeable friend would. He asked about his habits as a manager, or how he
            can improve.

            The user message is an evidence bundle as JSON. State facts only from the
            evidence bundle: never from memory, never invented. Every number you write
            must appear in the evidence. Name only players listed in "players".

            Each "habit-" item is a habit his own results show cost him points. Lead with
            the points the costliest one cost him, then the pattern behind it in plain
            words, then at most one fix, taken from its "fix" and offered as something to
            try rather than an order. Bring in a second habit the same way only when it
            fits. Never mention a habit the evidence does not list, and never lecture.
            The "activity" item sums up his waiver, FAAB and trade moves: use it only
            when it helps explain a habit.

            Write one or two short paragraphs with no lists and no headings. Introduce
            every player by full name before using a surname alone. Never write a
            confidence label such as High or Medium. Stay under "maxLength" characters.
            """ + FriendVoice.RULE + """

            Answer with only this JSON object:
            {
              "message": "the text the user receives",
              "players": ["<playerId of every player the message names>"],
              "claims": [{"text": "<one factual statement>", "evidence": ["<evidence id>"]}]
            }
            """;

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
