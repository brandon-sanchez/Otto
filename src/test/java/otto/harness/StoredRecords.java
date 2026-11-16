package otto.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import otto.records.DecisionRecord;
import otto.records.DecisionRecords;
import otto.records.RunKind;

/** Reads back the Decision Records a scenario left in the harness's storage. */
public final class StoredRecords {

    private static final String RUN_PREFIX = "run=";
    private static final String RUN_SUFFIX = ".json";

    private StoredRecords() {
    }

    /**
     * Returns the league's records of one kind, oldest first, or an empty
     * list when the league has none.
     */
    public static List<DecisionRecord> of(DecisionRecords records, String storageDir,
            String userId, String leagueId, RunKind kind) {
        Path league = Path.of(storageDir, "decision-records", "records", "user=" + userId,
                "league=" + leagueId);
        if (!Files.exists(league)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(league)) {
            return files
                    .map(file -> file.getFileName().toString())
                    .map(name -> name.substring(RUN_PREFIX.length(),
                            name.length() - RUN_SUFFIX.length()))
                    .map(runId -> records.read(userId, leagueId, runId).orElseThrow())
                    .filter(record -> record.kind() == kind)
                    .sorted(Comparator.comparing(DecisionRecord::startedAt))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
