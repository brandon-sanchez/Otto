package otto;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import otto.aws.AwsDocumentBackend;
import otto.aws.DocumentPlacement;
import otto.aws.DynamoDbBackend;
import otto.aws.S3BundleBackend;
import otto.aws.S3FeedObjects;
import otto.harness.FakeDynamoDb;
import otto.harness.FakeS3;
import otto.storage.ConcurrentDocumentChange;
import otto.storage.DocumentBackend;
import otto.storage.JsonStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the deployed assistant does with its documents. The nflverse
 * feeds go to S3 one object each; the other big ones go to S3 as one
 * object a run; the small ones go to DynamoDB one at a time; and two
 * entry points writing at once cost neither of them their work.
 */
class AwsStorageScenarioTest {

    private static final String BUCKET = "otto-documents";
    private static final String TABLE = "otto-records";
    private static final String KEY = "documents.json";
    private static final String PLAY_BY_PLAY = "nflverse-play-by-play";
    private static final String PLAY_BY_PLAY_KEY = "nflverse/nflverse-play-by-play.json";
    private static final String WEEKLY_STATS = "nflverse-weekly-stats";

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
        S3BundleBackend bundle = new S3BundleBackend(s3, BUCKET);
        return new AwsDocumentBackend(new S3FeedObjects(s3, BUCKET, bundle), bundle,
                new DynamoDbBackend(dynamo, TABLE));
    }

    /** A stand-in for one stored nflverse feed. */
    record Feed(String season, List<String> rows) {
    }

    @Test
    void eachFeedIsAnObjectOfItsOwnOutsideTheBundle() {
        store.write(PLAY_BY_PLAY, new Feed("2026", List.of("pass", "run")));
        store.write("snapshot-current", new Snapshot("2", List.of("Kamara")));
        store.flush();

        assertThat(s3.storedAsText(PLAY_BY_PLAY_KEY)).contains("pass");
        assertThat(s3.storedAsText(KEY)).contains("snapshot-current")
                .doesNotContain(PLAY_BY_PLAY);
    }

    @Test
    void aCheckThatReadsOnlyItsOwnDocumentsNeverFetchesPlayByPlay() {
        store.write(PLAY_BY_PLAY, new Feed("2026", List.of("pass")));
        store.write(WEEKLY_STATS, new Feed("2026", List.of("week 1")));
        store.write("snapshot-current", new Snapshot("2", List.of("Kamara")));
        store.flush();

        JsonStore check = new JsonStore(backend());
        check.read("snapshot-current", Snapshot.class);
        check.read(WEEKLY_STATS, Feed.class);

        assertThat(s3.readsOf(PLAY_BY_PLAY_KEY)).isZero();
    }

    @Test
    void anUnchangedFeedIsConfirmedWithoutABodyAndNotParsedAgain() {
        store.write(PLAY_BY_PLAY, new Feed("2026", List.of("pass")));
        store.flush();
        JsonStore reader = new JsonStore(backend());
        Feed first = reader.read(PLAY_BY_PLAY, Feed.class).orElseThrow();
        int served = s3.bodiesServed();

        Feed again = reader.read(PLAY_BY_PLAY, Feed.class).orElseThrow();

        assertThat(s3.readsOf(PLAY_BY_PLAY_KEY)).isEqualTo(2);
        assertThat(s3.bodiesServed()).as("the second read is a 304").isEqualTo(served);
        assertThat(again).isSameAs(first);
    }

    @Test
    void aFeedTheFeedJobRewroteIsReadOnTheVeryNextRead() {
        store.write(PLAY_BY_PLAY, new Feed("2026", List.of("pass")));
        store.flush();
        JsonStore check = new JsonStore(backend());
        check.read(PLAY_BY_PLAY, Feed.class);

        store.write(PLAY_BY_PLAY, new Feed("2026", List.of("pass", "sack")));
        store.flush();

        assertThat(check.read(PLAY_BY_PLAY, Feed.class))
                .contains(new Feed("2026", List.of("pass", "sack")));
    }

    @Test
    void aFeedRewrittenWhileStillInTheBundleLeavesTheBundleInTheSameFlush() {
        s3.writeBehindTheCaller(KEY, """
                {"snapshot-current":{"week":"2","starters":["Kamara"]},\
                "nflverse-play-by-play":{"season":"2026","rows":["pass"]}}""".getBytes());
        DocumentBackend backend = backend();

        backend.store(PLAY_BY_PLAY, """
                {"season":"2026","rows":["pass","sack"]}""".getBytes());
        backend.flush();

        assertThat(s3.storedAsText(PLAY_BY_PLAY_KEY)).contains("sack");
        assertThat(s3.storedAsText(KEY)).contains("snapshot-current")
                .doesNotContain(PLAY_BY_PLAY);
    }

    @Test
    void aFeedStillInTheBundleIsReadOnceAndMovedOutOfIt() {
        s3.writeBehindTheCaller(KEY, """
                {"snapshot-current":{"week":"2","starters":["Kamara"]},\
                "nflverse-play-by-play":{"season":"2026","rows":["pass"]}}""".getBytes());

        assertThat(store.read(PLAY_BY_PLAY, Feed.class))
                .contains(new Feed("2026", List.of("pass")));
        store.flush();

        assertThat(s3.storedAsText(PLAY_BY_PLAY_KEY)).contains("pass");
        assertThat(s3.storedAsText(KEY)).contains("snapshot-current")
                .doesNotContain(PLAY_BY_PLAY);
        assertThat(new JsonStore(backend()).read(PLAY_BY_PLAY, Feed.class))
                .contains(new Feed("2026", List.of("pass")));
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
    void alertDeliveryStateWritesThroughBeforeTheRunEnds() {
        store.write("alert-delivery-key:abc", Map.of("alertId", 1));
        store.write("alert-delivery-id:1", Map.of("status", "SENT"));

        assertThat(dynamo.writes()).isEqualTo(2);
        assertThat(dynamo.holds("alert-delivery-key:abc")).isTrue();
        assertThat(dynamo.holds("alert-delivery-id:1")).isTrue();
        assertThat(s3.writes()).isZero();
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
                .isInstanceOf(ConcurrentDocumentChange.class)
                .hasMessageContaining("event-log");
        assertThat(s3.storedAsText(KEY)).contains("the user tapped done");
    }

    @Test
    void aRefusedRunDoesNotApplyItsDocumentsToTheNextOne() {
        store.write("event-log", List.of("mine"));
        s3.writeBehindTheCaller(KEY, "{\"event-log\":[\"theirs\"]}".getBytes());
        assertThatThrownBy(store::flush)
                .isInstanceOf(ConcurrentDocumentChange.class);

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
                "telegram-updates", "alert-delivery-key:abc", "alert-delivery-id:1");

        List<String> feeds = List.of("nflverse-play-by-play", "nflverse-ftn-charting");

        assertThat(big).allMatch(name -> DocumentPlacement.of(name) != DocumentPlacement.SMALL);
        assertThat(small).allMatch(name -> DocumentPlacement.of(name) == DocumentPlacement.SMALL);
        assertThat(feeds).allMatch(name -> DocumentPlacement.of(name) == DocumentPlacement.FEED);
    }

    /**
     * A document nobody has placed goes to the store that cannot be
     * outgrown. The other way round, the mistake would surface as a
     * failed write in the middle of a season.
     */
    @Test
    void aDocumentNobodyHasPlacedGoesToTheStoreWithNoSizeLimit() {
        assertThat(DocumentPlacement.of("nflverse-something-new"))
                .isEqualTo(DocumentPlacement.FEED);
        assertThat(DocumentPlacement.of("a-document-added-next-season"))
                .isEqualTo(DocumentPlacement.BUNDLE);
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
