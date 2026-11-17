package otto.aws;

import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Level;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import otto.records.DecisionRecords;
import otto.records.RedactedCredentials;

/**
 * The one entry point that may delete a Decision Record. The webhook
 * invokes it once the user confirms, with {@code {"userId": "..."}}, and
 * it calls {@link DecisionRecords#deleteUser}. Its role is the only one
 * granted delete and governance-bypass rights on the records bucket, and
 * the bucket policy lets only it and the account root through, so the
 * functions that run every day cannot delete a record whatever goes
 * wrong in them.
 *
 * <p>It runs without the Spring context. It needs one S3 client, and
 * starting the application for it would cost seconds on every cold
 * start while the user waits for the confirmation.
 */
public class AccountDeletionHandler implements RequestHandler<Map<String, Object>, String> {

    static final String USER_ID = "userId";

    private static final String RECORDS_BUCKET = "OTTO_RECORDS_BUCKET";

    static {
        // Without Spring Boot to configure it, logback logs everything at
        // DEBUG, the SDK's wire chatter included.
        if (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)
                instanceof ch.qos.logback.classic.Logger root) {
            root.setLevel(Level.INFO);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionHandler.class);

    private final DecisionRecords records;

    /** Deletes from the bucket the stack names in {@code OTTO_RECORDS_BUCKET}. */
    public AccountDeletionHandler() {
        this(new DecisionRecords(
                new S3RecordObjects(AwsConfig.newS3Client(), recordsBucket()),
                new RedactedCredentials(List.of())));
    }

    private static String recordsBucket() {
        String bucket = System.getenv(RECORDS_BUCKET);
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException(RECORDS_BUCKET + " is not set");
        }
        return bucket;
    }

    AccountDeletionHandler(DecisionRecords records) {
        this.records = records;
    }

    /**
     * Deletes every record, outcome record and stored article text of
     * the user the request names, every version included.
     *
     * @return {@code "deleted"} once nothing of the user's is left
     * @throws IllegalArgumentException when the request names no usable
     *         user id
     * @throws IllegalStateException when S3 refused to delete an object;
     *         invoking again deletes what is left
     */
    @Override
    public String handleRequest(Map<String, Object> request, Context context) {
        if (!(request.get(USER_ID) instanceof String userId)) {
            throw new IllegalArgumentException("The request names no user whose records to delete");
        }
        try {
            records.deleteUser(userId);
        } catch (RuntimeException e) {
            log.error("Could not delete every Decision Record of one user", e);
            throw e;
        }
        log.info("Deleted every Decision Record of one user");
        return "deleted";
    }
}
