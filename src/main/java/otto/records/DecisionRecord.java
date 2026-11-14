package otto.records;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The retained account of one recommendation run: what Otto knew,
 * calculated and sent, kept so the advice can be reconstructed and
 * judged later.
 *
 * <p>A record is read back years after it was written, by code that
 * has moved on. The envelope and the parts this package owns are
 * typed; snapshots of other domain objects - the league and its rules,
 * the roster, the preferences, the comparisons and the model's request
 * and response - are captured as JSON at write time, so a field renamed
 * in those types later never makes an old record unreadable.
 *
 * <p>There is no field for conversation history. Unrelated chat is
 * kept out of a record by its shape, not by a filter that could miss
 * something.
 *
 * @param schemaVersion the shape this record was written in,
 *        {@link #SCHEMA_VERSION} for every record written now
 * @param runId sortable by time; see {@link #newRunId(Instant)}
 * @param league the league's identity and rules as Otto read them
 * @param preferences the preferences active for this run
 * @param comparisons what Otto computed between the candidates
 * @param exchanges every model call the run made, in order
 */
public record DecisionRecord(
        int schemaVersion,
        String runId,
        RunKind kind,
        Instant startedAt,
        String userId,
        String leagueId,
        JsonNode league,
        RosterContext roster,
        JsonNode preferences,
        List<Candidate> candidates,
        JsonNode comparisons,
        List<Evidence> evidence,
        Versions versions,
        List<ModelExchange> exchanges,
        Outcome outcome) {

    public static final int SCHEMA_VERSION = 1;

    private static final DateTimeFormatter RUN_STARTED =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    public DecisionRecord {
        candidates = List.copyOf(candidates);
        evidence = List.copyOf(evidence);
        exchanges = List.copyOf(exchanges);
        if (evidence.stream().map(Evidence::id).distinct().count() != evidence.size()) {
            throw new IllegalArgumentException("Evidence ids repeat within run " + runId);
        }
    }

    /**
     * A new run id, such as {@code 20261010T153000Z-3f9a1c2e}. The start
     * time leads so a league's records list in the order they ran; the
     * random tail keeps two runs that start in the same second apart.
     * A retried save converges only under the same run id, so a caller
     * that may be retried across invocations derives its run id from a
     * stable identity of the run instead of calling this again.
     */
    public static String newRunId(Instant startedAt) {
        return RUN_STARTED.format(startedAt) + "-"
                + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
    }

    DecisionRecord withEvidence(List<Evidence> evidence) {
        return new DecisionRecord(schemaVersion, runId, kind, startedAt, userId, leagueId,
                league, roster, preferences, candidates, comparisons, evidence, versions,
                exchanges, outcome);
    }
}
