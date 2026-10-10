package otto.records;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One call to the model, and what the checks found wrong with its
 * answer.
 *
 * @param attempt 1 for the first call, 2 for the correction attempt
 * @param checkFailures empty when the answer passed every check
 */
public record ModelExchange(int attempt, JsonNode request, JsonNode response,
        List<String> checkFailures) {

    public ModelExchange {
        checkFailures = List.copyOf(checkFailures);
    }
}
