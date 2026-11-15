package otto.model;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the {@link ModelChain} is built: the order it asks providers in,
 * its circuit breaker, and the two fallback providers. OpenAI is set up
 * under {@code spring.ai.openai}, where Spring AI reads it.
 *
 * @param order provider names, first asked first: {@code openai},
 *        {@code anthropic} and {@code gemini}
 */
@ConfigurationProperties(prefix = "otto.models")
public record ModelProperties(List<String> order, Breaker breaker, Provider anthropic,
        Provider gemini) {

    public ModelProperties {
        order = List.copyOf(order);
    }

    /**
     * When a provider is left alone.
     *
     * @param failures consecutive failed calls that open the breaker
     * @param cooldown how long an open breaker stays open before one
     *        trial call is let through
     */
    public record Breaker(int failures, Duration cooldown) {
    }

    /**
     * One fallback provider.
     *
     * @param apiKey blank leaves the provider out of the chain
     * @param baseUrl the API root, without a version path
     * @param timeout the longest one HTTP request may take
     * @param maxRetries retries of a rate-limited, failed or timed-out
     *        request before the chain moves on
     */
    public record Provider(String apiKey, String baseUrl, String model, Duration timeout,
            int maxRetries) {

        boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }
}
