package otto.records;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import otto.storage.JsonStore;

/**
 * What each run already decided, for callers that derive a run's id from
 * what it is about and repeat it every Check until it is done. A run
 * whose outcome is known is never asked of the model again.
 *
 * <p>The outcome comes from the run's Decision Record. When the record
 * cannot be saved or read, the outcome is kept in a small document in
 * its place, so a records store that keeps failing costs one model call
 * per run, not one per Check. Only the outcome is kept there, never the
 * record: the record carries the model's requests, and only
 * {@link DecisionRecords} redacts credentials out of them.
 */
@Component
public class RunOutcomes {

    private static final String DOCUMENT = "unsaved-run-outcomes";

    /** How many unsaved runs the document remembers, newest last. */
    static final int MAX_KEPT = 100;

    private static final Logger log = LoggerFactory.getLogger(RunOutcomes.class);

    private final DecisionRecords records;
    private final JsonStore store;

    /** Keeps records through {@code records} and unsaved outcomes in {@code store}. */
    public RunOutcomes(DecisionRecords records, JsonStore store) {
        this.records = records;
        this.store = store;
    }

    /** One run whose record could not be saved, and what it decided. */
    record Unsaved(String runId, Outcome outcome) {
    }

    /** The stored document: unsaved runs, oldest first. */
    record Document(List<Unsaved> runs) {

        Document {
            runs = runs == null ? List.of() : List.copyOf(runs);
        }
    }

    /**
     * Returns the outcome a run reached already, or empty when it has
     * not been made. Never throws: a record that cannot be read falls
     * back to the outcome kept when its save failed.
     */
    public Optional<Outcome> earlier(String userId, String leagueId, String runId) {
        try {
            Optional<Outcome> recorded = records.read(userId, leagueId, runId)
                    .map(DecisionRecord::outcome);
            if (recorded.isPresent()) {
                return recorded;
            }
        } catch (RuntimeException e) {
            log.warn("Cannot read Decision Record {}; using the outcome kept beside it", runId, e);
        }
        return unsaved().runs().stream()
                .filter(run -> run.runId().equals(runId))
                .map(Unsaved::outcome)
                .findFirst();
    }

    /**
     * Stores a run's record and returns the outcome its run id stands
     * for: the record's own, or that of an earlier attempt of the run
     * whose save landed first (see {@link DecisionRecords#saveFirst}).
     * Never throws: when the record cannot be saved, the failure is
     * logged and its outcome is kept in its place.
     *
     * <p>Side effects: the record's writes, or one document write when
     * they fail.
     */
    public Outcome record(DecisionRecord record) {
        try {
            return records.saveFirst(record).outcome();
        } catch (RuntimeException e) {
            log.error("Cannot save Decision Record {}; keeping its outcome so the run is not "
                    + "made again", record.runId(), e);
            keep(new Unsaved(record.runId(), record.outcome()));
            return record.outcome();
        }
    }

    private void keep(Unsaved run) {
        try {
            List<Unsaved> runs = new ArrayList<>(unsaved().runs().stream()
                    .filter(earlier -> !earlier.runId().equals(run.runId()))
                    .toList());
            runs.add(run);
            store.write(DOCUMENT, new Document(
                    runs.subList(Math.max(0, runs.size() - MAX_KEPT), runs.size())));
        } catch (RuntimeException e) {
            log.error("Cannot keep the outcome of run {} either", run.runId(), e);
        }
    }

    private Document unsaved() {
        try {
            return store.read(DOCUMENT, Document.class).orElse(new Document(List.of()));
        } catch (RuntimeException e) {
            log.warn("Cannot read the unsaved run outcomes", e);
            return new Document(List.of());
        }
    }
}
