package otto;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import otto.aws.AwsDocumentBackend;
import otto.aws.DocumentPlacement;
import otto.aws.DynamoDbBackend;
import otto.aws.S3BundleBackend;
import otto.harness.FakeDynamoDb;
import otto.harness.FakeS3;
import otto.storage.DocumentBackend;
import otto.storage.JsonStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the deployed assistant does with its documents. The big ones go
 * to S3 as one object a run; the small ones go to DynamoDB one at a
 * time; and two entry points writing at once cost neither of them
 * their work.
 */
class AwsStorageScenarioTest {

    private static final String BUCKET = "otto-documents";
    private static final String TABLE = "otto-records";
    private static final String KEY = "documents.json";

    private FakeS3 s3;
    private FakeDynamoDb dynamo;
    private JsonStore store;

    /** A big document and a small one, in the language the app stores. */
    record Snapshot(String week, List<String> starters) {
    }

    record Settings(boolean alertsOn, double edgeThreshold) {
    }

    @BeforeEach
    void newStorage() {
        s3 = new FakeS3();
        dynamo = new FakeDynamoDb();
        store = new JsonStore(backend());
    }

    private DocumentBackend backend() {
        return new AwsDocumentBackend(
                new S3BundleBackend(s3, BUCKET), new DynamoDbBackend(dynamo, TABLE));
    }

    @Test
    void aDocumentWithNoStoredValueReadsEmpty() {
        assertThat(store.read("snapshot-current", Snapshot.class)).isEmpty();
        assertThat(store.read("settings", Settings.class)).isEmpty();
    }

    @Test
    void aRunOfBigDocumentsCostsOneWrite() {
        store.write("snapshot-current", new Snapshot("2", List.of("Kamara")));
        store.write("snapshot-previous", new Snapshot("1", List.of("Pollard")));
        store.write("event-log", List.of("dropped"));

        assertThat(s3.writes()).as("nothing is stored before the run ends").isZero();

        store.flush();

        assertThat(s3.writes()).isEqualTo(1);
        assertThat(s3.storedAsText(KEY))
                .contains("snapshot-current", "snapshot-previous", "event-log");
    }

    @Test
    void aBigDocumentReadsBackWhatTheSameRunWrote() {
        store.write("snapshot-current", new Snapshot("2", List.of("Kamara")));

        assertThat(store.read("snapshot-current", Snapshot.class))
                .contains(new Snapshot("2", List.of("Kamara")));
    }

    @Test
    void aBigDocumentSurvivesTheRunThatWroteIt() {
        store.write("snapshot-current", new Snapshot("2", List.of("Kamara")));
        store.flush();

        JsonStore laterRun = new JsonStore(backend());

        assertThat(laterRun.read("snapshot-current", Snapshot.class))
                .contains(new Snapshot("2", List.of("Kamara")));
    }

    @Test
    void aSmallRecordIsStoredAtOnceAndNotInTheBundle() {
        store.write("settings", new Settings(true, 1.0));

        assertThat(dynamo.writes()).isEqualTo(1);
        assertThat(dynamo.holds("settings")).isTrue();
        assertThat(store.read("settings", Settings.class)).contains(new Settings(true, 1.0));

        store.flush();

        assertThat(s3.writes()).as("a small record never reaches the bundle").isZero();
    }

    @Test
    void theOtherEntryPointsWriteIsKeptWhenThisOneLands() {
        store.write("snapshot-current", new Snapshot("2", List.of("Kamara")));
        store.flush();

        // The webhook appends to the Event Log between this run's read
        // and its write, the way a button tap does.
        JsonStore check = new JsonStore(backend());
        check.read("snapshot-current", Snapshot.class);
        s3.writeBehindTheCaller(KEY, """
                {"snapshot-current":{"week":"2","starters":["Kamara"]},\
                "event-log":["ignored the flex alert"]}""".getBytes());

        check.write("snapshot-current", new Snapshot("3", List.of("Nacua")));
        check.flush();

        assertThat(s3.storedAsText(KEY))
                .as("the run that landed second keeps both writers' work")
                .contains("ignored the flex alert")
                .contains("Nacua");
    }

    /**
     * The Event Log is the one document both entry points append to. A
     * run that read it, appended, and met the other writer's append
     * cannot write its own copy over the top - the user's tap is in the
     * other one.
     */
    @Test
    void aTapTheOtherEntryPointStoredIsNotWrittenOver() {
        store.write("event-log", List.of("alert sent"));
        store.flush();

        JsonStore check = new JsonStore(backend());
        check.read("event-log", Object.class);
        s3.writeBehindTheCaller(KEY, """
                {"event-log":["alert sent","the user tapped done"]}""".getBytes());

        check.write("event-log", List.of("alert sent", "alert sent again"));

        assertThatThrownBy(check::flush)
                .isInstanceOf(S3BundleBackend.ConcurrentDocumentChange.class)
                .hasMessageContaining("event-log");
        assertThat(s3.storedAsText(KEY)).contains("the user tapped done");
    }

    @Test
    void aRefusedRunDoesNotApplyItsDocumentsToTheNextOne() {
        store.write("event-log", List.of("mine"));
        s3.writeBehindTheCaller(KEY, "{\"event-log\":[\"theirs\"]}".getBytes());
        assertThatThrownBy(store::flush)
                .isInstanceOf(S3BundleBackend.ConcurrentDocumentChange.class);

        store.write("settings", new Settings(true, 1.0));
        store.flush();

        assertThat(s3.storedAsText(KEY)).contains("theirs").doesNotContain("mine");
    }

    @Test
    void aRunThatChangedNothingReReadsWithoutABody() {
        store.write("snapshot-current", new Snapshot("2", List.of("Kamara")));
        store.flush();
        int servedByTheFirstRun = s3.bodiesServed();

        store.read("snapshot-current", Snapshot.class);
        store.read("event-log", Object.class);

        assertThat(s3.bodiesServed())
                .as("the held bundle is confirmed, not fetched again")
                .isEqualTo(servedByTheFirstRun);
    }

    @Test
    void aBundleThatIsNotAJsonObjectIsRefusedRatherThanRead() {
        s3.writeBehindTheCaller(KEY, "[\"not an object\"]".getBytes());

        assertThatThrownBy(() -> store.read("snapshot-current", Snapshot.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(KEY);
    }

    @Test
    void everyDocumentTheAssistantStoresHasAPlace() {
        List<String> big = List.of("snapshot-current", "snapshot-previous", "player-directory",
                "defense-versus-position", "event-log", "nflverse-weekly-stats",
                "nflverse-depth-charts", "nflverse-weekly-rosters", "nflverse-player-ids");
        List<String> small = List.of("settings", "watchlist", "watchlist-observations",
                "mutes", "conversation", "last-check", "alert-id-sequence",
                "telegram-updates");

        assertThat(big).allMatch(DocumentPlacement::isBig);
        assertThat(small).noneMatch(DocumentPlacement::isBig);
    }

    /**
     * A document nobody has placed goes to the store that cannot be
     * outgrown. The other way round, the mistake would surface as a
     * failed write in the middle of a season.
     */
    @Test
    void aDocumentNobodyHasPlacedGoesToTheStoreWithNoSizeLimit() {
        assertThat(DocumentPlacement.isBig("nflverse-something-new")).isTrue();
        assertThat(DocumentPlacement.isBig("a-document-added-next-season")).isTrue();
    }

    @Test
    void anEmptyRunStoresNothing() {
        store.flush();

        assertThat(s3.writes()).isZero();
        assertThat(dynamo.writes()).isZero();
    }

    @Test
    void aSmallRecordSurvivesTheRunThatWroteIt() {
        store.write("watchlist", List.of("Nacua"));

        Optional<List<String>> read = new JsonStore(backend())
                .read("watchlist", otto.storage.OttoJson.MAPPER.getTypeFactory()
                        .constructCollectionType(List.class, String.class));

        assertThat(read).contains(List.of("Nacua"));
    }
}
