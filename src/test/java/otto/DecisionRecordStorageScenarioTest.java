package otto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import otto.aws.S3RecordObjects;
import otto.aws.SsmSecrets;
import otto.harness.FakeS3;
import otto.records.Candidate;
import otto.records.DecisionRecord;
import otto.records.DecisionRecords;
import otto.records.Evidence;
import otto.records.EvidenceStatus;
import otto.records.FileRecordObjects;
import otto.records.ModelExchange;
import otto.records.Outcome;
import otto.records.RosterContext;
import otto.records.RunKind;
import otto.records.Versions;
import otto.storage.OttoJson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What Otto keeps of each recommendation run. A Decision Record is
 * stored once, per user and per league, without credentials, with its
 * copied article text beside it on a shorter clock, and all of it goes
 * when the user's account does.
 */
class DecisionRecordStorageScenarioTest {

    private static final String BUCKET = "otto-decision-records";
    private static final String MODEL_KEY = "sk-test-0123456789abcdef";
    private static final String BOT_TOKEN = "123456:bot-token-abcdef";

    private static final Instant STARTED = Instant.parse("2026-10-10T15:30:00Z");

    private FakeS3 s3;
    private DecisionRecords records;

    @BeforeEach
    void newStorage() {
        s3 = new FakeS3().withGovernanceRetention();
        records = new DecisionRecords(new S3RecordObjects(s3, BUCKET),
                List.of(MODEL_KEY, BOT_TOKEN, "", "unset"));
    }

    @Test
    void aSavedRecordReadsBackWholeFromUnderItsUserAndLeague() {
        DecisionRecord saved = record("owner", "league-1", "20261010T153000Z-3f9a1c2e");

        records.save(saved);

        assertThat(s3.keys()).contains(
                "records/user=owner/league=league-1/run=20261010T153000Z-3f9a1c2e.json");
        assertThat(records.read("owner", "league-1", "20261010T153000Z-3f9a1c2e"))
                .contains(saved);
    }

    @Test
    void aRunThatWasNeverSavedReadsEmpty() {
        assertThat(records.read("owner", "league-1", "20261010T153000Z-00000000")).isEmpty();
    }

    @Test
    void copiedArticleTextIsStoredApartAndItsExpiryLeavesTheFacts() {
        DecisionRecord saved = record("owner", "league-1", "20261010T153000Z-3f9a1c2e");
        records.save(saved);

        String recordKey = "records/user=owner/league=league-1/run=20261010T153000Z-3f9a1c2e.json";
        String textKey =
                "article-text/user=owner/league=league-1/run=20261010T153000Z-3f9a1c2e.json";
        assertThat(s3.storedAsText(recordKey))
                .as("the record object never holds copied article text")
                .doesNotContain("limited practice Thursday");
        assertThat(s3.storedAsText(textKey)).contains("limited practice Thursday");

        s3.expire(textKey);

        DecisionRecord facts = records.read("owner", "league-1", "20261010T153000Z-3f9a1c2e")
                .orElseThrow();
        assertThat(facts.evidence()).allSatisfy(
                evidence -> assertThat(evidence.sourceText()).isEmpty());
        Evidence report = facts.evidence().getFirst();
        assertThat(report.url()).contains("https://example.com/injury-report");
        assertThat(report.publishedAt()).contains(Instant.parse("2026-10-09T18:00:00Z"));
        assertThat(report.retrievedAt()).isEqualTo(Instant.parse("2026-10-10T15:29:00Z"));
        assertThat(report.status()).isEqualTo(EvidenceStatus.READ);
        assertThat(facts.candidates()).isEqualTo(saved.candidates());
        assertThat(facts.outcome()).isEqualTo(saved.outcome());
    }

    @Test
    void aRecordWithNoCopiedTextStoresNoArticleText() {
        DecisionRecord saved = record("owner", "league-1", "20261010T153000Z-3f9a1c2e");
        records.save(new DecisionRecord(saved.schemaVersion(), saved.runId(), saved.kind(),
                saved.startedAt(), saved.userId(), saved.leagueId(), saved.league(),
                saved.roster(), saved.preferences(), saved.candidates(), saved.comparisons(),
                List.of(), saved.versions(), saved.exchanges(), saved.outcome()));

        assertThat(s3.keys()).noneMatch(key -> key.startsWith("article-text/"));
    }

    @Test
    void aCredentialInARunNeverReachesStorage() {
        records.save(record("owner", "league-1", "20261010T153000Z-3f9a1c2e", MODEL_KEY));
        records.save(record("owner", "league-1", "20261010T153100Z-4a5b6c7d", BOT_TOKEN));

        assertThat(s3.keys()).hasSize(4).allSatisfy(key -> assertThat(s3.storedAsText(key))
                .doesNotContain(MODEL_KEY)
                .doesNotContain(BOT_TOKEN)
                .contains("[redacted]"));
        assertThat(s3.storedAsText(
                "records/user=owner/league=league-1/run=20261010T153000Z-3f9a1c2e.json"))
                .contains("Bearer [redacted]");
    }

    @Test
    void aCredentialShortEnoughToOccurByChanceNeverReshapesARecord() {
        String recordKey = "records/user=owner/league=league-1/run=20261010T153000Z-3f9a1c2e.json";
        FakeS3 unredacted = new FakeS3();
        new DecisionRecords(new S3RecordObjects(unredacted, BUCKET), List.of())
                .save(record("owner", "league-1", "20261010T153000Z-3f9a1c2e"));

        new DecisionRecords(new S3RecordObjects(s3, BUCKET), Arrays.asList("1", null))
                .save(record("owner", "league-1", "20261010T153000Z-3f9a1c2e"));

        JsonNode stored = json(s3.storedAsText(recordKey));
        assertThat(s3.storedAsText(recordKey)).contains("[redacted]");
        assertThat(shape(stored))
                .as("every property name, number and nesting survives redaction")
                .isEqualTo(shape(json(unredacted.storedAsText(recordKey))))
                .contains("$.exchanges[0].request.headers.Authorization",
                        "$.comparisons.9221.projection=11.4");
    }

    @Test
    void aSecretAddedToSsmIsRedactedWithNoCodeChange() {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("otto.some-later-secret", "ssm-only-secret-42");
        parameters.put("otto.cleared-secret", null);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources()
                .addFirst(new MapPropertySource(SsmSecrets.SOURCE_NAME, parameters));
        records = new DecisionRecords(new S3RecordObjects(s3, BUCKET), environment);

        records.save(record("owner", "league-1", "20261010T153000Z-3f9a1c2e",
                "ssm-only-secret-42"));

        assertThat(s3.keys()).hasSize(2).allSatisfy(key -> assertThat(s3.storedAsText(key))
                .doesNotContain("ssm-only-secret-42")
                .contains("[redacted]"));
    }

    @Test
    void aRetriedSaveConvergesAndARunIdIsNeverRewritten() {
        DecisionRecord saved = record("owner", "league-1", "20261010T153000Z-3f9a1c2e");
        records.save(saved);
        records.save(saved);

        assertThat(s3.writes()).as("one record and one article text, stored once").isEqualTo(2);

        DecisionRecord different = new DecisionRecord(saved.schemaVersion(), saved.runId(),
                saved.kind(), saved.startedAt(), saved.userId(), saved.leagueId(),
                saved.league(), saved.roster(), saved.preferences(), saved.candidates(),
                saved.comparisons(), saved.evidence(), saved.versions(), saved.exchanges(),
                new Outcome.Withheld("the board failed its checks twice"));

        assertThatThrownBy(() -> records.save(different))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("run=20261010T153000Z-3f9a1c2e");
        assertThat(records.read("owner", "league-1", "20261010T153000Z-3f9a1c2e"))
                .contains(saved);
    }

    @Test
    void aSaveThatLosesARaceToItsOwnRetryConverges() {
        DecisionRecord saved = record("owner", "league-1", "20261010T153000Z-3f9a1c2e");
        s3.loseNextPutToAnIdenticalWriter();

        records.save(saved);

        assertThat(records.read("owner", "league-1", "20261010T153000Z-3f9a1c2e"))
                .contains(saved);
    }

    @Test
    void deletingAUserRemovesEveryRecordInEveryLeagueAndNoOneElses() {
        records.save(record("owner", "league-1", "20261010T153000Z-3f9a1c2e"));
        records.save(record("owner", "league-1", "20261017T153000Z-4b5c6d7e"));
        records.save(record("owner", "league-1", "20261024T153000Z-5c6d7e8f"));
        records.save(record("owner", "league-2", "20261011T090000Z-0b1c2d3e"));
        records.save(record("owner", "league-2", "20261018T090000Z-1c2d3e4f"));
        records.save(record("owner-2", "league-1", "20261010T153000Z-77aa88bb"));
        s3.expire("article-text/user=owner/league=league-1/run=20261010T153000Z-3f9a1c2e.json");

        records.deleteUser("owner");

        assertThat(s3.versionsUnder("records/user=owner/"))
                .as("no version is left to read back").isEmpty();
        assertThat(s3.versionsUnder("article-text/user=owner/"))
                .as("no version and no delete marker an expiry left is kept").isEmpty();
        assertThat(s3.keys()).containsExactlyInAnyOrder(
                "records/user=owner-2/league=league-1/run=20261010T153000Z-77aa88bb.json",
                "article-text/user=owner-2/league=league-1/run=20261010T153000Z-77aa88bb.json");
        assertThat(records.read("owner", "league-1", "20261010T153000Z-3f9a1c2e")).isEmpty();
        assertThat(records.read("owner-2", "league-1", "20261010T153000Z-77aa88bb")).isPresent();
    }

    @Test
    void anIdThatWouldReshapeAKeyIsRefused() {
        assertThatThrownBy(() -> records.save(record("owner/../x", "league-1",
                "20261010T153000Z-3f9a1c2e")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> records.deleteUser("owner/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(s3.keys()).isEmpty();
    }

    @Test
    void aRunIdSortsByWhenTheRunStarted() {
        String earlier = DecisionRecord.newRunId(STARTED);
        String later = DecisionRecord.newRunId(STARTED.plusSeconds(1));

        assertThat(earlier).matches("20261010T153000Z-[0-9a-f]{8}");
        assertThat(earlier).isLessThan(later);
    }

    @Test
    void theLaptopKeepsRecordsAsFiles(@TempDir Path dir) {
        DecisionRecords local = new DecisionRecords(new FileRecordObjects(dir), List.of(MODEL_KEY));
        DecisionRecord saved = record("owner", "league-1", "20261010T153000Z-3f9a1c2e");

        local.save(saved);
        local.save(saved);

        assertThat(dir.resolve(
                "records/user=owner/league=league-1/run=20261010T153000Z-3f9a1c2e.json"))
                .exists();
        assertThat(local.read("owner", "league-1", "20261010T153000Z-3f9a1c2e"))
                .contains(saved);

        local.deleteUser("owner");

        assertThat(local.read("owner", "league-1", "20261010T153000Z-3f9a1c2e")).isEmpty();
    }

    @Test
    void onTheLaptopExactlyOneOfManyRacingWritersWins(@TempDir Path dir) throws Exception {
        FileRecordObjects objects = new FileRecordObjects(dir);
        String key = "records/user=owner/league=league-1/run=20261010T153000Z-3f9a1c2e.json";
        List<Callable<Boolean>> writers = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            byte[] bytes = ("{\"writer\":" + i + "}").getBytes();
            writers.add(() -> objects.putIfAbsent(key, bytes));
        }

        List<Boolean> won = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(writers.size())) {
            for (Future<Boolean> result : pool.invokeAll(writers)) {
                won.add(result.get());
            }
        }

        assertThat(won).containsOnlyOnce(true);
        try (Stream<Path> files = Files.walk(dir)) {
            assertThat(files.filter(Files::isRegularFile).toList())
                    .as("no temp file is left behind")
                    .containsExactly(dir.resolve(key));
        }
    }

    private DecisionRecord record(String userId, String leagueId, String runId) {
        return record(userId, leagueId, runId, "not-a-credential");
    }

    /**
     * A waiver board run with every part filled in. The secret is what
     * the model request was authorized with, and one copied article
     * quotes it too, so a record stored unredacted would show it in
     * both objects.
     */
    private DecisionRecord record(String userId, String leagueId, String runId,
            String secret) {
        return new DecisionRecord(
                DecisionRecord.SCHEMA_VERSION,
                runId,
                RunKind.WAIVER_BOARD,
                STARTED,
                userId,
                leagueId,
                json("""
                        {"leagueId":"%s","name":"Dynasty Bros","scoring":{"rec":1.0}}"""
                        .formatted(leagueId)),
                new RosterContext(json("{\"starters\":[\"4034\",\"6794\"]}"), Optional.empty()),
                json("{\"avoid\":[\"rookie quarterbacks\"]}"),
                List.of(
                        new Candidate("9221", "Jaylen Wright", true, "lead back with Achane out"),
                        new Candidate("8150", "Kendre Miller", false, "already rostered")),
                json("{\"9221\":{\"projection\":11.4,\"replacement\":7.2}}"),
                List.of(
                        new Evidence("injury-report", "team site",
                                Optional.of("https://example.com/injury-report"),
                                Optional.of(Instant.parse("2026-10-09T18:00:00Z")),
                                Instant.parse("2026-10-10T15:29:00Z"),
                                "availability", EvidenceStatus.READ,
                                Optional.of("Achane was limited practice Thursday.")),
                        new Evidence("beat-writer", "beat writer",
                                Optional.empty(), Optional.empty(),
                                Instant.parse("2026-10-10T15:29:30Z"),
                                "role", EvidenceStatus.READ,
                                Optional.of("Pasted with token " + secret + " by mistake.")),
                        new Evidence("paywalled", "subscription site",
                                Optional.of("https://example.com/paywalled"), Optional.empty(),
                                Instant.parse("2026-10-10T15:29:40Z"),
                                "role", EvidenceStatus.FAILED, Optional.empty())),
                new Versions("feeds-2026-10-10T15:00Z", "53eefd3", "waiver-board-1", "gpt-5.6-luna"),
                List.of(
                        new ModelExchange(1, json("""
                                {"headers":{"Authorization":"Bearer %s"},"prompt":"rank these"}"""
                                .formatted(secret)),
                                json("{\"text\":\"Bid 40 on Wright\"}"),
                                List.of("bid exceeds the verified budget")),
                        new ModelExchange(2, json("{\"prompt\":\"correct the bid\"}"),
                                json("{\"text\":\"Bid 12 on Wright\"}"), List.of())),
                new Outcome.Sent("Bid 12 on Jaylen Wright."));
    }

    /** Every path in a document, with the value at each leaf that is not a string. */
    private static List<String> shape(JsonNode node) {
        List<String> paths = new ArrayList<>();
        shape(node, "$", paths);
        return paths;
    }

    private static void shape(JsonNode node, String path, List<String> paths) {
        paths.add(node.isValueNode() && !node.isTextual() ? path + "=" + node : path);
        if (node.isObject()) {
            node.properties().forEach(property ->
                    shape(property.getValue(), path + "." + property.getKey(), paths));
        }
        for (int i = 0; node.isArray() && i < node.size(); i++) {
            shape(node.get(i), path + "[" + i + "]", paths);
        }
    }

    private static JsonNode json(String text) {
        try {
            return OttoJson.MAPPER.readTree(text);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
