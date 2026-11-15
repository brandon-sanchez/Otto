package otto.alerts;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.advice.AdviceAnswer;
import otto.advice.AdviceForm;
import otto.advice.AdviceResult;
import otto.advice.AdviceWriter;
import otto.advice.EvidenceBundle;
import otto.ask.LeagueWeek;
import otto.records.Candidate;
import otto.records.DecisionRecord;
import otto.records.Outcome;
import otto.records.RosterContext;
import otto.records.RunIds;
import otto.records.RunKind;
import otto.records.RunOutcomes;
import otto.research.PreSendResearch;
import otto.settings.SettingsStore;
import otto.snapshot.RosterSnapshot;
import otto.storage.OttoJson;

/**
 * Writes the text of bench-edge, lineup-legality and final-pregame
 * Alerts. The model writes each one from an evidence bundle and
 * {@link AdviceWriter} checks it. When the model is down or its advice
 * fails twice, an urgent Alert (a slot that scores zero, or a starter
 * still uncertain as his game nears) goes out as code-written verified
 * facts; any other Alert is withheld.
 *
 * <p>Every run writes one Decision Record. Its run id is derived from
 * the Alert key and what the evidence says, so a later Check over the
 * same evidence finds the run already made and repeats its outcome
 * instead of asking the model again. New evidence is a new run.
 */
@Component
public class LineupAdvice {

    private static final String RUN_ID_PREFIX = "alert-";

    private final AdviceWriter writer;
    private final RunOutcomes runs;
    private final SettingsStore settings;
    private final PreSendResearch preSendResearch;
    private final String userId;

    public LineupAdvice(AdviceWriter writer, RunOutcomes runs, SettingsStore settings,
            PreSendResearch preSendResearch, OttoProperties properties) {
        this.writer = writer;
        this.runs = runs;
        this.settings = settings;
        this.preSendResearch = preSendResearch;
        this.userId = properties.userId();
    }

    /**
     * The text for an edge or legality candidate.
     *
     * @param research the facts {@link PreSendResearch} read for the
     *        candidate's player right before sending; its newest report
     *        joins the evidence. For a bench edge, the bench player the
     *        swap would start has his news read here too, so neither
     *        side of the swap rests on cached news.
     * @return empty when the advice is withheld
     */
    public Optional<AlertText.Advised> forProblem(String alertKey, AlertCandidate candidate,
            Map<String, String> research, LeagueWeek leagueWeek, RosterSnapshot roster,
            Instant now) {
        return problem(alertKey, candidate, research, leagueWeek, roster, now, null);
    }

    /**
     * The final pregame warning for an edge or legality candidate still
     * open as his game nears, read as {@link #forProblem} reads it.
     *
     * @param kickoff the game start of the candidate's player
     * @return empty when the advice is withheld
     */
    public Optional<AlertText.Advised> forFinalWarning(String alertKey, AlertCandidate candidate,
            Map<String, String> research, LeagueWeek leagueWeek, RosterSnapshot roster,
            Instant now, Instant kickoff) {
        return problem(alertKey, candidate, research, leagueWeek, roster, now, kickoff);
    }

    /** Writes advice for a lineup problem; {@code kickoff} is null unless it is a final warning. */
    private Optional<AlertText.Advised> problem(String alertKey, AlertCandidate candidate,
            Map<String, String> research, LeagueWeek leagueWeek, RosterSnapshot roster,
            Instant now, Instant kickoff) {
        Instant readAt = leagueWeek.snapshot().at();
        LineupEvidence.Draft draft = switch (candidate.source()) {
            case EDGE -> LineupEvidence.edge(candidate, roster, leagueWeek.week(), readAt, now,
                    kickoff);
            case LEGALITY -> LineupEvidence.legality(candidate, roster, leagueWeek.week(), readAt,
                    now, kickoff);
            default -> throw new IllegalArgumentException(
                    "No lineup advice for a " + candidate.source() + " candidate");
        };
        LineupEvidence.Draft withNews = draft.withNews(candidate.playerId(), research, now);
        if (candidate.source() == AlertCandidate.Source.EDGE) {
            String startInstead = candidate.facts().get(BenchEdgeDetector.BENCH_ID);
            withNews = withNews.withNews(startInstead, preSendResearch.factsFor(startInstead), now);
        }
        return write(alertKey, withNews, leagueWeek, roster, now);
    }

    /**
     * The final pregame warning for a starter whose only problem is an
     * uncertain designation.
     *
     * @param research the facts read for the starter right before sending
     * @return empty when the advice is withheld
     */
    public Optional<AlertText.Advised> forImpairedStarter(String alertKey, String playerId,
            Map<String, String> research, LeagueWeek leagueWeek, RosterSnapshot roster,
            Instant now, Instant kickoff) {
        LineupEvidence.Draft draft = LineupEvidence.impaired(playerId, roster, leagueWeek.week(),
                leagueWeek.snapshot().at(), now, kickoff);
        return write(alertKey, draft.withNews(playerId, research, now), leagueWeek, roster, now);
    }

    /** The run an Alert's advice came from, and how it ended. */
    private record Run(String runId, Outcome outcome) {
    }

    private Optional<AlertText.Advised> write(String alertKey, LineupEvidence.Draft draft,
            LeagueWeek leagueWeek, RosterSnapshot roster, Instant now) {
        return sent(run(alertKey, draft, leagueWeek, roster, now,
                answer -> answer.move().isEmpty() && draft.holdStaysSilent()
                        ? new Outcome.Withheld(
                                "The model advised no change, which is not sent unasked.")
                        : new Outcome.Sent(answer.message())));
    }

    /**
     * Repeats the outcome of an earlier run over the same evidence, or
     * asks the model and records a new run.
     *
     * @param approve the outcome of advice that passed its checks
     */
    private Run run(String alertKey, LineupEvidence.Draft draft,
            LeagueWeek leagueWeek, RosterSnapshot roster, Instant now,
            Function<AdviceAnswer, Outcome> approve) {
        String leagueId = leagueWeek.league().leagueId();
        String runId = runId(alertKey, draft.bundle());
        Outcome outcome = runs.earlierOrMade(userId, leagueId, runId, () -> {
            AdviceForm<AdviceAnswer> form = writer.lineup();
            AdviceResult<AdviceAnswer> result = writer.write(draft.bundle(), form);
            return Optional.of(DecisionRecord.forRun(runId, RunKind.ALERT, now, userId, leagueId,
                            OttoJson.MAPPER.valueToTree(leagueWeek.league()))
                    .roster(new RosterContext(OttoJson.MAPPER.valueToTree(roster),
                            Optional.empty()))
                    .preferences(OttoJson.MAPPER.valueToTree(settings.current()))
                    .candidates(candidates(draft))
                    .comparisons(comparisons(draft.bundle()))
                    .evidence(draft.bundle().recorded())
                    .versions(draft.bundle().versions(form, result))
                    .exchanges(result.exchanges())
                    .outcome(outcome(result, draft, approve)));
        }).orElseThrow();
        return new Run(runId, outcome);
    }

    private static Outcome outcome(AdviceResult<AdviceAnswer> result,
            LineupEvidence.Draft draft, Function<AdviceAnswer, Outcome> approve) {
        return switch (result) {
            case AdviceResult.Approved<AdviceAnswer> approved -> approve.apply(approved.answer());
            case AdviceResult.Rejected<AdviceAnswer> rejected -> draft.urgentFacts()
                    .<Outcome>map(Outcome.Sent::new)
                    .orElseGet(() -> new Outcome.Withheld(
                            "The advice failed its checks twice: "
                                    + String.join(" ", rejected.failures())));
            case AdviceResult.Unavailable<AdviceAnswer> unavailable -> draft.urgentFacts()
                    .<Outcome>map(Outcome.Sent::new)
                    .orElseGet(() -> new Outcome.Withheld(unavailable.reason()));
        };
    }

    /** Returns the advice to send now when the run ended with a sent message. */
    private static Optional<AlertText.Advised> sent(Run run) {
        return run.outcome() instanceof Outcome.Sent sent
                ? Optional.of(new AlertText.Advised(run.runId(), sent.message()))
                : Optional.empty();
    }

    private static String runId(String alertKey, EvidenceBundle bundle) {
        return RunIds.stable(RUN_ID_PREFIX, alertKey, bundle.identity());
    }

    private static List<Candidate> candidates(LineupEvidence.Draft draft) {
        return draft.bundle().players().stream()
                .map(player -> new Candidate(player.playerId(), player.fullName(), true,
                        draft.candidateReasons().get(player.playerId())))
                .toList();
    }

    private static JsonNode comparisons(EvidenceBundle bundle) {
        Map<String, Object> projections = new LinkedHashMap<>();
        bundle.players().forEach(player -> projections.put(player.playerId(),
                player.projectedPoints().orElse(null)));
        Map<String, Object> comparisons = new LinkedHashMap<>();
        comparisons.put("projectedPoints", projections);
        comparisons.put("legalMoves", bundle.legalMoves());
        comparisons.put("noChangeAllowed", bundle.noChangeAllowed());
        return OttoJson.MAPPER.valueToTree(comparisons);
    }
}
