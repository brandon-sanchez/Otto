package otto.alerts;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import otto.OttoApplication;
import otto.OttoProperties;
import otto.advice.AdviceResult;
import otto.advice.AdviceWriter;
import otto.advice.EvidenceBundle;
import otto.advice.Sha256;
import otto.ask.LeagueWeek;
import otto.records.Candidate;
import otto.records.DecisionRecord;
import otto.records.DecisionRecords;
import otto.records.Evidence;
import otto.records.EvidenceStatus;
import otto.records.Outcome;
import otto.records.RosterContext;
import otto.records.RunKind;
import otto.records.Versions;
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
    private static final int RUN_ID_HASH_LENGTH = 32;

    private static final Logger log = LoggerFactory.getLogger(LineupAdvice.class);

    private final AdviceWriter writer;
    private final DecisionRecords records;
    private final SettingsStore settings;
    private final String userId;
    private final String model;

    public LineupAdvice(AdviceWriter writer, DecisionRecords records, SettingsStore settings,
            OttoProperties properties,
            @Value("${spring.ai.openai.chat.options.model:unknown}") String model) {
        this.writer = writer;
        this.records = records;
        this.settings = settings;
        this.userId = properties.userId();
        this.model = model;
    }

    /**
     * The checked text of one Alert.
     *
     * @param runId the Decision Record that explains it, empty for an
     *        Alert phrased from its Recommendation, which makes no run
     */
    public record Written(String text, Optional<String> runId) {
    }

    /**
     * The text for an edge or legality candidate.
     *
     * @param kickoff the game start of the candidate's player when this
     *        is the final pregame warning, or null otherwise
     * @return empty when the advice is withheld
     */
    public Optional<Written> forProblem(String alertKey, AlertCandidate candidate,
            LeagueWeek leagueWeek, RosterSnapshot roster, Instant now, Instant kickoff) {
        Instant readAt = leagueWeek.snapshot().at();
        LineupEvidence.Draft draft = switch (candidate.source()) {
            case EDGE -> LineupEvidence.edge(candidate, roster, leagueWeek.week(), readAt, now,
                    kickoff);
            case LEGALITY -> LineupEvidence.legality(candidate, roster, leagueWeek.week(), readAt,
                    now, kickoff);
            default -> throw new IllegalArgumentException(
                    "No lineup advice for a " + candidate.source() + " candidate");
        };
        return write(alertKey, draft, leagueWeek, roster, now);
    }

    /**
     * The final pregame warning for a starter whose only problem is an
     * uncertain designation.
     *
     * @return empty when the advice is withheld
     */
    public Optional<Written> forImpairedStarter(String alertKey, String playerId,
            LeagueWeek leagueWeek, RosterSnapshot roster, Instant now, Instant kickoff) {
        return write(alertKey, LineupEvidence.impaired(playerId, roster, leagueWeek.week(),
                leagueWeek.snapshot().at(), now, kickoff), leagueWeek, roster, now);
    }

    private Optional<Written> write(String alertKey, LineupEvidence.Draft draft,
            LeagueWeek leagueWeek, RosterSnapshot roster, Instant now) {
        String leagueId = leagueWeek.league().leagueId();
        String runId = runId(alertKey, draft.bundle());
        Optional<Outcome> earlier = earlierOutcome(leagueId, runId);
        if (earlier.isPresent()) {
            return sent(earlier.get(), runId);
        }

        AdviceResult result = writer.write(draft.bundle());
        Outcome outcome = outcome(result, draft);
        save(new DecisionRecord(DecisionRecord.SCHEMA_VERSION, runId, RunKind.ALERT, now, userId,
                leagueId, OttoJson.MAPPER.valueToTree(leagueWeek.league()),
                new RosterContext(OttoJson.MAPPER.valueToTree(roster), Optional.empty()),
                OttoJson.MAPPER.valueToTree(settings.current()), candidates(draft),
                comparisons(draft.bundle()), evidence(draft.bundle()), versions(draft.bundle()),
                result.exchanges(), outcome));
        return sent(outcome, runId);
    }

    private static Outcome outcome(AdviceResult result, LineupEvidence.Draft draft) {
        return switch (result) {
            case AdviceResult.Approved approved when approved.answer().move().isEmpty()
                    && draft.holdStaysSilent() ->
                    new Outcome.Withheld("The model advised no change, which is not sent unasked.");
            case AdviceResult.Approved approved -> new Outcome.Sent(approved.answer().message());
            case AdviceResult.Rejected rejected -> draft.urgentFacts()
                    .<Outcome>map(Outcome.Sent::new)
                    .orElseGet(() -> new Outcome.Withheld(
                            "The advice failed its checks twice: "
                                    + String.join(" ", rejected.failures())));
            case AdviceResult.Unavailable unavailable -> draft.urgentFacts()
                    .<Outcome>map(Outcome.Sent::new)
                    .orElseGet(() -> new Outcome.Withheld(unavailable.reason()));
        };
    }

    private static Optional<Written> sent(Outcome outcome, String runId) {
        return outcome instanceof Outcome.Sent sent
                ? Optional.of(new Written(sent.message(), Optional.of(runId)))
                : Optional.empty();
    }

    private Optional<Outcome> earlierOutcome(String leagueId, String runId) {
        try {
            return records.read(userId, leagueId, runId).map(DecisionRecord::outcome);
        } catch (RuntimeException e) {
            log.warn("Cannot read Decision Record {}; asking the model again", runId, e);
            return Optional.empty();
        }
    }

    private void save(DecisionRecord record) {
        try {
            records.save(record);
        } catch (RuntimeException e) {
            log.error("Cannot save Decision Record {}", record.runId(), e);
        }
    }

    private static String runId(String alertKey, EvidenceBundle bundle) {
        return RUN_ID_PREFIX
                + Sha256.hex(alertKey + "\n" + bundle.identity()).substring(0, RUN_ID_HASH_LENGTH);
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

    private static List<Evidence> evidence(EvidenceBundle bundle) {
        return bundle.evidence().stream()
                .map(item -> new Evidence(item.id(), item.source(), Optional.empty(),
                        item.publishedAt(), item.retrievedAt(), item.scoringBasis(),
                        EvidenceStatus.READ, Optional.empty()))
                .toList();
    }

    private Versions versions(EvidenceBundle bundle) {
        String code = Objects.requireNonNullElse(
                OttoApplication.class.getPackage().getImplementationVersion(), "dev");
        return new Versions(bundle.identity(), code, AdviceWriter.promptVersion(), model);
    }
}
