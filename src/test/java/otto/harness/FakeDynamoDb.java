package otto.harness;

import java.util.HashMap;
import java.util.Map;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

/** A DynamoDB that lives in memory: one item per document name. */
public class FakeDynamoDb implements DynamoDbClient {

    private final Map<String, Map<String, AttributeValue>> items = new HashMap<>();

    private int writes;

    @Override
    public GetItemResponse getItem(GetItemRequest request) {
        Map<String, AttributeValue> item = items.get(key(request.key()));
        return GetItemResponse.builder().item(item == null ? Map.of() : item).build();
    }

    @Override
    public PutItemResponse putItem(PutItemRequest request) {
        items.put(key(request.item()), request.item());
        writes++;
        return PutItemResponse.builder().build();
    }

    public boolean holds(String name) {
        return items.containsKey(name);
    }

    public int writes() {
        return writes;
    }

    private String key(Map<String, AttributeValue> attributes) {
        return attributes.get("name").s();
    }

    @Override
    public String serviceName() {
        return DynamoDbClient.SERVICE_NAME;
    }

    @Override
    public void close() {
    }
}
