package otto.records;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One call to a model, or one provider the model chain passed over
 * without calling, and what the checks found wrong with its answer.
 *
 * @param attempt 1 for the first call to a provider, 2 for its
 *        correction attempt
 * @param response null-valued JSON when no answer came back
 * @param checkFailures empty when the answer passed every check;
 *        otherwise why it failed, or why the chain moved past the
 *        provider
 * @param provider the provider asked, such as {@code openai}; null in
 *        a stored record that does not name one
 * @param model the model id asked; null in a stored record that does
 *        not name one
 */
public record ModelExchange(int attempt, JsonNode request, JsonNode response,
        List<String> checkFailures, String provider, String model) {

    public ModelExchange {
        checkFailures = List.copyOf(checkFailures);
    }
}
