package otto.harness;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

/** A DynamoDB that lives in memory: one item per document name. */
public class FakeDynamoDb implements DynamoDbClient {

    private static final Pattern NOT_EXISTS = Pattern.compile("attribute_not_exists\\((#\\w+)\\)");
    private static final Pattern COMPARISON = Pattern.compile("(#\\w+) (=|<=) (:\\w+)");

    private final Map<String, Map<String, AttributeValue>> items = new HashMap<>();

    private int writes;

    @Override
    public GetItemResponse getItem(GetItemRequest request) {
        Map<String, AttributeValue> item = items.get(key(request.key()));
        return GetItemResponse.builder().item(item == null ? Map.of() : item).build();
    }

    /**
     * Stores the item, refusing it as DynamoDB would when its condition
     * does not hold. Only the conditions {@code DynamoDbBackend} writes
     * are understood: comparisons and {@code attribute_not_exists},
     * joined by OR.
     */
    @Override
    public PutItemResponse putItem(PutItemRequest request) {
        if (request.conditionExpression() != null
                && !holds(request.conditionExpression(), items.get(key(request.item())),
                        request.expressionAttributeNames(), request.expressionAttributeValues())) {
            throw ConditionalCheckFailedException.builder()
                    .message("The conditional request failed")
                    .build();
        }
        items.put(key(request.item()), request.item());
        writes++;
        return PutItemResponse.builder().build();
    }

    private static boolean holds(String condition, Map<String, AttributeValue> item,
            Map<String, String> names, Map<String, AttributeValue> values) {
        for (String clause : condition.split(" OR ")) {
            Matcher missing = NOT_EXISTS.matcher(clause.strip());
            if (missing.matches()) {
                if (item == null || !item.containsKey(names.get(missing.group(1)))) {
                    return true;
                }
                continue;
            }
            Matcher comparison = COMPARISON.matcher(clause.strip());
            if (!comparison.matches()) {
                throw new IllegalArgumentException("FakeDynamoDb cannot read " + clause);
            }
            AttributeValue stored = item == null ? null : item.get(names.get(comparison.group(1)));
            AttributeValue given = values.get(comparison.group(3));
            if (stored != null && compares(stored, comparison.group(2), given)) {
                return true;
            }
        }
        return false;
    }

    private static boolean compares(AttributeValue stored, String operator,
            AttributeValue given) {
        if (operator.equals("=")) {
            return stored.equals(given);
        }
        return new BigDecimal(stored.n()).compareTo(new BigDecimal(given.n())) <= 0;
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
