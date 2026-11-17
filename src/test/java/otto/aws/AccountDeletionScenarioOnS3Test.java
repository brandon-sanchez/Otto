package otto.aws;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

import otto.harness.FakeS3;
import otto.harness.SampleRecords;
import otto.records.DecisionRecords;
import otto.records.RedactedCredentials;
import otto.storage.OttoJson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deployed deletion: the webhook invokes the account-deletion
 * function, which removes every stored version of the user's records
 * from the locked, versioned bucket.
 */
class AccountDeletionScenarioOnS3Test {

    private static final String OWNER = "777001";
    private static final String FUNCTION = "OttoStack-AccountDeletion";

    private FakeS3 s3;
    private DecisionRecords records;
    private AccountDeletionHandler deleter;

    @BeforeEach
    void ownerHasAdviceHistoryUnderGovernanceLock() {
        s3 = new FakeS3().withGovernanceRetention();
        records = new DecisionRecords(new S3RecordObjects(s3, "records-bucket"),
                new RedactedCredentials(List.of()));
        deleter = new AccountDeletionHandler(records);
        SampleRecords.storeRun(records, OWNER, "league-1", "20261010T153000Z-3f9a1c2e");
        SampleRecords.storeRun(records, OWNER, "league-1", "20261017T153000Z-4b5c6d7e");
        SampleRecords.storeRun(records, OWNER, "league-2", "20261011T090000Z-0b1c2d3e");
        SampleRecords.storeRun(records, "777002", "league-1", "20261010T153000Z-77aa88bb");
        s3.expire("article-text/user=777001/league=league-1/run=20261010T153000Z-3f9a1c2e.json");
    }

    @Test
    void confirmingLeavesNoVersionOfAnyRecordOutcomeOrArticleText() {
        new LambdaRecordsEraser(invoking(deleter), FUNCTION).eraseUser(OWNER);

        assertThat(s3.versionsUnder("records/user=777001/")).isEmpty();
        assertThat(s3.versionsUnder("outcomes/user=777001/")).isEmpty();
        assertThat(s3.versionsUnder("article-text/user=777001/"))
                .as("no version and no delete marker an expiry left").isEmpty();
        assertThat(s3.keys()).containsExactlyInAnyOrder(
                "records/user=777002/league=league-1/run=20261010T153000Z-77aa88bb.json",
                "article-text/user=777002/league=league-1/run=20261010T153000Z-77aa88bb.json",
                "outcomes/user=777002/league=league-1/run=20261010T153000Z-77aa88bb.json");
    }

    @Test
    void theWebhookNamesTheFunctionAndTheUserAndNothingElse() {
        List<InvokeRequest> sent = new ArrayList<>();
        LambdaClient lambda = lambda(request -> {
            sent.add(request);
            return answered("\"deleted\"");
        });

        new LambdaRecordsEraser(lambda, FUNCTION).eraseUser(OWNER);

        assertThat(sent).singleElement().satisfies(request -> {
            assertThat(request.functionName()).isEqualTo(FUNCTION);
            assertThat(request.payload().asUtf8String()).isEqualTo("{\"userId\":\"777001\"}");
        });
    }

    @Test
    void aDeletionThatFailedIsNotReportedAsDone() {
        LambdaClient failing = lambda(request -> failed("{\"errorMessage\":\"denied\"}"));

        assertThatThrownBy(() -> new LambdaRecordsEraser(failing, FUNCTION).eraseUser(OWNER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unhandled")
                .as("what the function stored stays in its own log")
                .hasMessageNotContaining("denied");
    }

    @Test
    void aRequestThatNamesNoUsableUserDeletesNothing() {
        Map<String, Object> noUser = new HashMap<>();
        noUser.put("userId", null);

        assertThatThrownBy(() -> deleter.handleRequest(noUser, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> deleter.handleRequest(Map.of("userId", "777001/"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> deleter.handleRequest(Map.of("userId", 777001), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(s3.versionsUnder("records/user=777001/")).hasSize(3);
    }

    @SuppressWarnings("unchecked")
    private static LambdaClient invoking(AccountDeletionHandler handler) {
        return lambda(request -> {
            try {
                Map<String, Object> event = OttoJson.MAPPER.readValue(
                        request.payload().asByteArray(), Map.class);
                return answered("\"" + handler.handleRequest(event, null) + "\"");
            } catch (Exception e) {
                return failed(e.toString());
            }
        });
    }

    private static LambdaClient lambda(Function<InvokeRequest, InvokeResponse> invoke) {
        return new LambdaClient() {
            @Override
            public InvokeResponse invoke(InvokeRequest request) {
                return invoke.apply(request);
            }

            @Override
            public String serviceName() {
                return SERVICE_NAME;
            }

            @Override
            public void close() {
            }
        };
    }

    private static InvokeResponse answered(String payload) {
        return InvokeResponse.builder().statusCode(200)
                .payload(SdkBytes.fromUtf8String(payload)).build();
    }

    /** How Lambda reports a function that threw: still a 200, with the error named. */
    private static InvokeResponse failed(String payload) {
        return InvokeResponse.builder().statusCode(200).functionError("Unhandled")
                .payload(SdkBytes.fromUtf8String(payload)).build();
    }
}
