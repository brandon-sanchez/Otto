package otto.aws;

import java.util.Map;
import java.util.Optional;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import otto.storage.DocumentBackend;

/**
 * The small records: Settings, the Watchlist, Mutes, the conversation,
 * the last Check and the Alert id sequence, one item each. Reads are
 * strongly consistent, because the webhook writes what the next Check
 * reads a moment later and an eventually consistent read could answer
 * with the record the user just changed away from.
 *
 * <p>Writes go straight through. These records are small and few, so
 * batching them would buy nothing and would cost the guarantee that a
 * stored Alert id is stored before the Alert goes out.
 */
public class DynamoDbBackend implements DocumentBackend {

    /** The partition key: the document name the caller asked for. */
    static final String NAME = "name";

    /** The document itself, held as bytes so no JSON is reshaped in transit. */
    static final String DOCUMENT = "document";

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
