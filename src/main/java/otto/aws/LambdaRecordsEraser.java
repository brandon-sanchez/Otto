package otto.aws;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

import otto.account.RecordsEraser;
import otto.storage.OttoJson;

/**
 * Erases a user's records by invoking {@link AccountDeletionHandler}'s
 * function and waiting for it to finish. The records bucket lets only
 * that function's role delete, so the webhook asks it rather than
 * holding delete rights of its own.
 */
public class LambdaRecordsEraser implements RecordsEraser {

    private final LambdaClient lambda;
    private final String functionName;

    /**
     * Invokes {@code functionName}, which is blank in a function the
     * stack does not let invoke it.
     */
    public LambdaRecordsEraser(LambdaClient lambda, String functionName) {
        this.lambda = lambda;
        this.functionName = functionName;
    }

    /**
     * Side effects: one synchronous Lambda invocation, which deletes
     * objects in the records bucket.
     *
     * @throws IllegalStateException when this function was given no
     *         deletion function, or the deletion function failed
     */
    @Override
    public void eraseUser(String userId) {
        if (functionName == null || functionName.isBlank()) {
            throw new IllegalStateException(
                    "This function was not given an account-deletion function to invoke");
        }
        InvokeResponse response = lambda.invoke(InvokeRequest.builder()
                .functionName(functionName)
                .invocationType(InvocationType.REQUEST_RESPONSE)
                .payload(SdkBytes.fromByteArray(payload(userId)))
                .build());
        if (response.functionError() != null) {
            throw new IllegalStateException("The account-deletion function failed ("
                    + response.functionError() + "): " + response.payload().asUtf8String());
        }
    }

    private static byte[] payload(String userId) {
        try {
            return OttoJson.MAPPER.writeValueAsBytes(
                    Map.of(AccountDeletionHandler.USER_ID, userId));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write the account-deletion request", e);
        }
    }
}
