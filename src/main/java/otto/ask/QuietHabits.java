package otto.ask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import otto.advice.AdviceAnswer;
import otto.advice.AdviceCheck;
import otto.advice.EvidenceBundle;
import otto.advice.EvidenceItem;
import otto.habits.HabitLean;

/**
 * Carries the user's costly habits into team analysis and trade ideas as
 * quiet context: the evidence tells the model how they tilt the advice,
 * and the check turns back any reply that names them. He hears about his
 * habits only from {@code review_habits}, when he asks.
 */
final class QuietHabits {

    static final String LEAN_ID = "lean";

    static final String RULE = """
            When "lean" is present, let it shape what you stress, and never mention it:
            say nothing about the user's habits, his past lineups or his past moves.
            """;

    private static final String SOURCE = "Otto's weekly habit capture of the user's own league";

    private static final String MEASURES = "how the habits the user's own results show cost "
            + "him points tilt these suggestions; it shapes the advice and is never named";

    private static final String STEADY = "their steady weekly role and per-game projection, "
            + "not any one big week";

    private static final Pattern HABIT_MENTION = Pattern.compile("(?i)\\bhabits?\\b"
            + "|\\bchas(?:e|es|ed|ing)\\b"
            + "|\\boverr(?:ide|ides|ode|iding|idden)\\b"
            + "|\\b(?:left|leave|leaves|leaving)\\b[^.!?]{0,40}\\bon (?:your|the) bench\\b"
            + "|\\byou (?:tend|keep|often|always|usually)\\b"
            + "|\\b(?:last|past) (?:week's|weeks') big games?\\b"
            + "|\\byour past (?:lineups?|moves|starts)\\b");

    private QuietHabits() {
    }

    static EvidenceBundle add(EvidenceBundle bundle, HabitLean lean, Instant now) {
        if (lean.quiet()) {
            return bundle;
        }
        Map<String, String> facts = new LinkedHashMap<>();
        if (!lean.depthAt().isEmpty()) {
            facts.put("weighDepthAt", String.join(", ", lean.depthAt()));
        }
        if (lean.steadyUsage()) {
            facts.put("pitchPlayersOn", STEADY);
        }
        List<EvidenceItem> evidence = new ArrayList<>(bundle.evidence());
        evidence.add(new EvidenceItem(LEAN_ID, SOURCE, Optional.empty(), now, MEASURES, facts));
        return new EvidenceBundle(bundle.task(), evidence, bundle.players(),
                bundle.legalMoves(), bundle.noChangeAllowed(), bundle.kickoffPhrase(),
                bundle.maxLength(), bundle.budget(), bundle.rosterPlans(), bundle.unknowns());
    }

    static AdviceCheck.Verdict<AdviceAnswer> failHabitMentions(
            AdviceCheck.Verdict<AdviceAnswer> verdict) {
        Optional<AdviceAnswer> answer = switch (verdict) {
            case AdviceCheck.Verdict.Passed<AdviceAnswer> passed -> Optional.of(passed.answer());
            case AdviceCheck.Verdict.Failed<AdviceAnswer> failed -> failed.answer();
        };
        if (answer.isEmpty()) {
            return verdict;
        }
        List<String> failures = new ArrayList<>(verdict.failures());
        failures.addAll(mentions(answer.get().message()));
        return AdviceCheck.Verdict.checked(answer.get(), failures);
    }

    private static List<String> mentions(String message) {
        List<String> failures = new ArrayList<>();
        Matcher matcher = HABIT_MENTION.matcher(message);
        while (matcher.find()) {
            failures.add(("\"%s\" brings up how he manages his team; leave his habits "
                    + "unnamed and let \"lean\" only shape what you suggest")
                    .formatted(matcher.group()));
        }
        return failures;
    }
}
