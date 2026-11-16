package otto.model;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the {@link ModelChain} is built: the order it asks providers in,
 * its circuit breaker, its time budget at each call site, and the two
 * fallback providers. OpenAI is set up under {@code spring.ai.openai},
 * where Spring AI reads it.
 *
 * @param order provider names, first asked first: {@code openai},
 *        {@code anthropic} and {@code gemini}
 */
@ConfigurationProperties(prefix = "otto.models")
public record ModelProperties(List<String> order, Breaker breaker, Budgets budgets,
        Provider anthropic, Provider gemini) {

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
     * The time one run of the chain may take, from its first call to its
     * last answer.
     *
     * @param deadline the whole run's limit
     * @param reserve the time held back for each later provider that may
     *        still be asked, so a provider that hangs leaves them a share
     */
    public record Budget(Duration deadline, Duration reserve) {

        public Budget {
            if (reserve.compareTo(deadline) >= 0) {
                throw new IllegalArgumentException("A reserve of " + reserve
                        + " leaves no time inside a deadline of " + deadline);
            }
        }
    }

    /**
     * The budget at each call site, and the one all of a Check's runs
     * share.
     *
     * @param ask a chat reply, which must fit the webhook function's
     *        60-second timeout with time left to send it
     * @param phrasing the wording of a Recommendation as an Alert
     * @param check the time from the start of a Check within which every
     *        one of its runs must end; it must fit the scheduled
     *        function's 300-second timeout with time left to send and
     *        store what the Check found
     */
    public record Budgets(Budget ask, Budget phrasing, Duration check) {
    }

    /**
     * One fallback provider.
     *
     * @param apiKey blank leaves the provider out of the chain
     * @param baseUrl the API root, without a version path
     * @param timeout the longest one HTTP request may take; the chain's
     *        deadline can stop a call sooner
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
