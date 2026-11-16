package otto.aws;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import otto.storage.DocumentBackend;
import otto.storage.Lease;

/**
 * The small records: Settings, the Watchlist, Mutes, the conversation,
 * the last Check, the Alert id sequence, Alert delivery records and
 * the research cache.
 * Reads are
 * strongly consistent, because the webhook writes what the next Check
 * reads a moment later and an eventually consistent read could answer
 * with the record the user just changed away from.
 *
 * <p>Writes go straight through. These records are small and few, so
 * batching them would buy nothing and would cost the guarantee that
 * pending and sent delivery state lands around the Telegram call.
 */
public class DynamoDbBackend implements DocumentBackend {

    /** The partition key: the document name the caller asked for. */
    static final String NAME = "name";

    /** The document itself, held as bytes so no JSON is reshaped in transit. */
    static final String DOCUMENT = "document";

    /** A lease's holder, beside its document so a write can be conditioned on it. */
    static final String LEASE_HOLDER = "leaseHolder";

    /** When a lease ends, in epoch milliseconds, beside its document for the same reason. */
    static final String LEASE_UNTIL = "leaseUntil";

    private static final Map<String, String> LEASE_NAMES = Map.of(
            "#name", NAME, "#holder", LEASE_HOLDER, "#until", LEASE_UNTIL);

    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoDbBackend(DynamoDbClient dynamo, String table) {
        this.dynamo = dynamo;
        this.table = table;
    }

    @Override
    public Optional<byte[]> load(String name) {
        Map<String, AttributeValue> item = dynamo.getItem(GetItemRequest.builder()
                .tableName(table)
                .key(Map.of(NAME, AttributeValue.fromS(name)))
                .consistentRead(true)
                .build()).item();
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(item.get(DOCUMENT).b().asByteArray());
    }

    /** Takes the lease in one conditional write, so two containers never both hold it. */
    @Override
    public boolean lease(String name, Lease lease, Instant now) {
        try {
            dynamo.putItem(leaseWrite(name, lease)
                    .conditionExpression(
                            "attribute_not_exists(#name) OR #holder = :holder OR #until <= :now")
                    .expressionAttributeNames(LEASE_NAMES)
                    .expressionAttributeValues(Map.of(
                            ":holder", AttributeValue.fromS(lease.holder()),
                            ":now", AttributeValue.fromN(String.valueOf(now.toEpochMilli()))))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    @Override
    public void release(String name, String holder) {
        try {
            dynamo.putItem(leaseWrite(name, new Lease(holder, Instant.EPOCH))
                    .conditionExpression("#holder = :holder")
                    .expressionAttributeNames(Map.of("#holder", LEASE_HOLDER))
                    .expressionAttributeValues(Map.of(":holder", AttributeValue.fromS(holder)))
                    .build());
        } catch (ConditionalCheckFailedException e) {
            // Another holder took the lease after this one's ran out; it is theirs to end.
        }
    }

    private PutItemRequest.Builder leaseWrite(String name, Lease lease) {
        return PutItemRequest.builder()
                .tableName(table)
                .item(Map.of(
                        NAME, AttributeValue.fromS(name),
                        DOCUMENT, AttributeValue.fromB(SdkBytes.fromByteArray(lease.bytes())),
                        LEASE_HOLDER, AttributeValue.fromS(lease.holder()),
                        LEASE_UNTIL, AttributeValue.fromN(
                                String.valueOf(lease.until().toEpochMilli()))));
    }

    @Override
    public void store(String name, byte[] json) {
        dynamo.putItem(PutItemRequest.builder()
                .tableName(table)
                .item(Map.of(
                        NAME, AttributeValue.fromS(name),
                        DOCUMENT, AttributeValue.fromB(SdkBytes.fromByteArray(json))))
                .build());
    }

}
