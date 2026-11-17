package otto.harness;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import otto.records.DecisionRecord;
import otto.records.DecisionRecords;
import otto.records.Evidence;
import otto.records.EvidenceStatus;
import otto.records.Outcome;
import otto.records.RunKind;
import otto.storage.OttoJson;

/**
 * Stores a user's advice history the way a run leaves it: a Decision
 * Record, the article text copied beside it, and the outcome record
 * that judges it.
 */
public final class SampleRecords {

    private static final Instant STARTED = Instant.parse("2026-10-10T15:30:00Z");

    private SampleRecords() {
    }

    /** Stores one run of each kind of object for the user in the league. */
    public static void storeRun(DecisionRecords records, String userId, String leagueId,
            String runId) {
        records.saveFirst(DecisionRecord.forRun(runId, RunKind.WAIVER_BOARD, STARTED, userId,
                        leagueId, OttoJson.MAPPER.createObjectNode().put("leagueId", leagueId))
                .evidence(List.of(new Evidence("injury-report", "team site",
                        Optional.of("https://example.com/injury-report"), Optional.empty(),
                        STARTED.minusSeconds(60), "availability", EvidenceStatus.READ,
                        Optional.of("Achane was limited in practice Thursday."))))
                .outcome(new Outcome.Sent("Bid 12 on Jaylen Wright.")));
        records.saveOutcome(userId, leagueId, runId, Map.of("judged", true));
    }
}
