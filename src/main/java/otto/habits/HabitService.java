package otto.habits;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.advice.AdviceAnswer;
import otto.advice.AdviceCheck;
import otto.advice.AdviceResult;
import otto.advice.AdviceWriter;
import otto.advice.EvidenceBundle;
import otto.check.WeekFacts;
import otto.directory.DirectoryPlayer;
import otto.directory.PlayerDirectory;
import otto.directory.PlayerDirectoryStore;
import otto.records.DecisionRecord;
import otto.records.Outcome;
import otto.records.RunIds;
import otto.records.RunKind;
import otto.records.RunOutcomes;
import otto.records.Versions;
import otto.sleeper.SleeperAdapter;
import otto.snapshot.Snapshot;
import otto.storage.OttoJson;

/**
 * Answers the user's question about his habits as a manager. Only the
 * habits his own results show cost him points are reported, and only
 * when he asks: nothing here ever sends a message by itself.
 *
 * <p>Code measures every habit and writes every number into an
 * {@link EvidenceBundle}; the model only explains them, and its answer
 * must pass {@link AdviceCheck} with one correction before it is used.
 * When there is no habit to explain, code writes the answer and the same
 * check holds it. Each answer is kept as a Decision Record whose run id
 * comes from its evidence, so asking again over unchanged results costs
 * no model call.
 */
@Component
public class HabitService {

    private static final String WITHHELD = "my answer about your habits did not pass my "
            + "fact checks, so I am not sending it";

    private final HabitLedger ledger;
    private final PlayerDirectoryStore directory;
    private final AdviceWriter writer;
    private final AdviceCheck check;
    private final RunOutcomes runs;
    private final String userId;

    public HabitService(HabitLedger ledger, PlayerDirectoryStore directory, AdviceWriter writer,
            AdviceCheck check, RunOutcomes runs, OttoProperties properties) {
        this.ledger = ledger;
        this.directory = directory;
        this.writer = writer;
        this.check = check;
        this.runs = runs;
        this.userId = properties.userId();
    }

    /** How one habits question was answered. */
    public sealed interface Answer {

        /** The checked message for the user, to be sent as written. */
        record Written(String message) implements Answer {
        }

        /** No answer can be given; {@code reason} says why, in words for the user. */
        record Unavailable(String reason) implements Answer {
        }
    }

    /**
     * Answers from the weekly capture, brought up to date.
     *
     * <p>Side effects: the capture's reads (see
     * {@link HabitLedger#current}), up to two model calls when a habit cost
     * him points, and one Decision Record write unless the same evidence
     * was answered before.
     */
    public Answer answer(SleeperAdapter.League league, Snapshot snapshot, WeekFacts week,
            Instant now) {
        Function<String, String> names = names();
        HabitReport report = Habits.measure(ledger.current(league, snapshot, week, now), names);
        EvidenceBundle bundle = HabitEvidence.bundle(report, names, league.waiverBudget(), now);
        String runId = RunIds.stable("habits-", league.leagueId(), bundle.identity());
        Optional<Outcome> earlier = runs.earlier(userId, league.leagueId(), runId);
        if (earlier.isPresent()) {
            return answer(earlier.get());
        }
        DecisionRecord.Builder record = DecisionRecord.forRun(runId, RunKind.HABITS, now,
                        userId, league.leagueId(), OttoJson.MAPPER.valueToTree(league))
                .evidence(bundle.recorded());
        if (report.costly().isEmpty()) {
            return answer(runs.record(record.versions(Versions.withoutModel(bundle.identity()))
                    .outcome(new Outcome.Sent(codeWritten(report, bundle)))));
        }
        HabitForm form = new HabitForm(check);
        AdviceResult<AdviceAnswer> result = writer.write(bundle, form);
        Outcome outcome;
        switch (result) {
            case AdviceResult.Approved<AdviceAnswer> approved ->
                    outcome = new Outcome.Sent(approved.answer().message());
            case AdviceResult.Rejected<AdviceAnswer> rejected -> outcome = new Outcome.Withheld(
                    "The habits answer failed its checks twice: "
                            + String.join(" ", rejected.failures()));
            case AdviceResult.Unavailable<AdviceAnswer> _ -> {
                return new Answer.Unavailable("I could not reach the model that writes this "
                        + "answer; ask me again in a little while");
            }
        }
        return answer(runs.record(record.versions(bundle.versions(form, result))
                .exchanges(result.exchanges())
                .outcome(outcome)));
    }

    /**
     * Returns how the user's costly habits tilt team analysis and trade
     * ideas, from the weekly capture brought up to date.
     *
     * <p>Side effects: the capture's reads (see
     * {@link HabitLedger#current}). No model call and no Decision Record.
     */
    public HabitLean lean(SleeperAdapter.League league, Snapshot snapshot, WeekFacts week,
            Instant now) {
        HabitHistory history = ledger.current(league, snapshot, week, now);
        return HabitLean.of(history, Habits.measure(history, Function.identity()));
    }

    /**
     * The answer code writes when there is no habit to explain, held to
     * the same check as a model's, so a number in it can never drift from
     * the evidence.
     *
     * @throws IllegalStateException when the code-written answer fails
     *         the check, which is a bug in it
     */
    private String codeWritten(HabitReport report, EvidenceBundle bundle) {
        String message = HabitEvidence.withoutHabits(report);
        List<String> failures = check.checkAnswer(bundle, new AdviceAnswer(message,
                Optional.empty(), List.of(), List.of(), List.of(), Optional.empty(), List.of()));
        if (!failures.isEmpty()) {
            throw new IllegalStateException("The code-written habits answer fails its check: "
                    + failures);
        }
        return message;
    }

    private static Answer answer(Outcome outcome) {
        return outcome instanceof Outcome.Sent sent
                ? new Answer.Written(sent.message())
                : new Answer.Unavailable(WITHHELD);
    }

    private Function<String, String> names() {
        Map<String, DirectoryPlayer> players = directory.read()
                .map(PlayerDirectory::players)
                .orElse(Map.of());
        return playerId -> Optional.ofNullable(players.get(playerId))
                .map(DirectoryPlayer::fullName)
                .orElse(playerId);
    }
}
