package otto.advice;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.format.Spacing;
import otto.format.TelegramHtml;
import otto.records.Candidate;
import otto.records.DecisionRecord;
import otto.records.Outcome;
import otto.records.RunIds;
import otto.records.RunKind;
import otto.records.RunOutcomes;
import otto.sleeper.SleeperAdapter;
import otto.storage.OttoJson;

/**
 * Writes one checked chat answer: the model writes it under a form,
 * {@link AdviceWriter} checks it, and the run is kept as a Decision
 * Record whatever the outcome.
 */
@Component
public class CheckedAdvice {

    private final AdviceWriter writer;
    private final RunOutcomes runs;
    private final String userId;

    public CheckedAdvice(AdviceWriter writer, RunOutcomes runs, OttoProperties properties) {
        this.writer = writer;
        this.runs = runs;
        this.userId = properties.userId();
    }

    /**
     * What one run is, for its Decision Record.
     *
     * @param runIdPrefix names the kind of run in its id
     * @param keys what identifies the run besides its bundle and start
     *        time, such as the league or the player asked about; may be
     *        empty
     * @param league the league document as Otto read it
     * @param candidates what the run chose between; may be empty
     * @param writeUp what the model writes, as a withheld record names
     *        it, such as {@code "explanation"}
     */
    public record Run(String runIdPrefix, List<String> keys, RunKind kind,
            SleeperAdapter.League league, List<Candidate> candidates, String writeUp) {

        public Run {
            keys = List.copyOf(keys);
            candidates = List.copyOf(candidates);
        }
    }

    /**
     * What the user reads when no checked answer reaches him.
     *
     * @param unreachable when no model answered
     * @param failedCheck when the answer failed its checks, or an earlier
     *        attempt of the same run was withheld
     */
    public record Fallbacks(String unreachable, String failedCheck) {
    }

    /**
     * One run's result.
     *
     * @param outcome the run's recorded outcome, which may be an earlier
     *        attempt's when its record was saved first
     */
    public record Checked(Outcome outcome, AdviceResult<AdviceAnswer> result) {

        /** True when the checked text goes to the user, directly or through the chat model. */
        public boolean reachesUser() {
            return outcome instanceof Outcome.Sent || outcome instanceof Outcome.HandedToChat;
        }

        /**
         * Returns the tool's answer: the facts built around the checked
         * text, or the fallback that says why there is none.
         *
         * @param facts builds the tool's facts from the checked text
         */
        public <A> ToolAnswer<A> answer(Function<String, A> facts, Fallbacks fallbacks) {
            return switch (outcome) {
                case Outcome.Sent sent -> ToolAnswer.of(facts.apply(sent.message()));
                case Outcome.HandedToChat handed -> ToolAnswer.of(facts.apply(handed.message()));
                default -> ToolAnswer.unavailable(
                        result instanceof AdviceResult.Unavailable<AdviceAnswer>
                                ? fallbacks.unreachable()
                                : fallbacks.failedCheck());
            };
        }
    }

    /**
     * Has the model write the answer and records the run.
     *
     * <p>Side effects: one or two model calls, and one Decision Record
     * of the run's kind.
     *
     * @param delivery how an approved answer reaches the user, which the
     *        record keeps as its outcome
     */
    public Checked write(EvidenceBundle bundle, AdviceForm<AdviceAnswer> form, Run run,
            Instant now, AnswerDelivery delivery) {
        return write(bundle, form, run, now, delivery, null);
    }

    /** Uses a verified, code-written answer when the model cannot explain the same facts. */
    public Checked write(EvidenceBundle bundle, AdviceForm<AdviceAnswer> form, Run run,
            Instant now, AnswerDelivery delivery, Supplier<String> verifiedFallback) {
        AdviceResult<AdviceAnswer> result = laidOut(writer.write(bundle, form));
        Outcome outcome = switch (result) {
            case AdviceResult.Approved<AdviceAnswer> approved ->
                delivered(approved.answer().message(), delivery);
            case AdviceResult.Rejected<AdviceAnswer> rejected ->
                fallbackOrWithheld(verifiedFallback, delivery,
                        "The %s failed its checks twice: ".formatted(run.writeUp())
                                + String.join(" ", rejected.failures()));
            case AdviceResult.Unavailable<AdviceAnswer> unavailable ->
                fallbackOrWithheld(verifiedFallback, delivery, unavailable.reason());
        };
        return new Checked(runs.record(record(bundle, form, run, result, now, outcome)), result);
    }

    private DecisionRecord record(EvidenceBundle bundle, AdviceForm<AdviceAnswer> form, Run run,
            AdviceResult<AdviceAnswer> result, Instant now, Outcome outcome) {
        List<String> parts = new ArrayList<>(run.keys());
        parts.add(bundle.identity());
        parts.add(now.toString());
        String runId = RunIds.stable(run.runIdPrefix(), parts.toArray(String[]::new));
        return DecisionRecord.forRun(runId, run.kind(), now, userId, run.league().leagueId(),
                        OttoJson.MAPPER.valueToTree(run.league()))
                .candidates(run.candidates())
                .evidence(bundle.recorded())
                .versions(bundle.versions(form, result))
                .exchanges(result.exchanges())
                .outcome(outcome);
    }

    /**
     * Returns the result with an approved message as Telegram shows it:
     * Markdown turned into the HTML it stands for by
     * {@link TelegramHtml#normalize} and crammed lines given room by
     * {@link Spacing#breathe}, so the Decision Record keeps the message
     * as the user reads it.
     */
    private static AdviceResult<AdviceAnswer> laidOut(AdviceResult<AdviceAnswer> result) {
        return result instanceof AdviceResult.Approved<AdviceAnswer> approved
                ? new AdviceResult.Approved<>(approved.answer().withMessage(Spacing.breathe(
                        TelegramHtml.normalize(approved.answer().message()))),
                        approved.exchanges())
                : result;
    }

    private static Outcome delivered(String message, AnswerDelivery delivery) {
        return switch (delivery) {
            case UNCHANGED -> new Outcome.Sent(message);
            case THROUGH_CHAT_MODEL -> new Outcome.HandedToChat(message);
        };
    }

    private static Outcome fallbackOrWithheld(Supplier<String> verifiedFallback,
            AnswerDelivery delivery, String reason) {
        return verifiedFallback == null
                ? new Outcome.Withheld(reason)
                : delivered(verifiedFallback.get(), delivery);
    }
}
