package otto.model;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * One circuit breaker per model provider. A provider whose calls failed
 * {@link ModelProperties.Breaker#failures()} times in a row is left
 * alone for the cooldown, so a provider that is down is not called on
 * every one-minute Check. After the cooldown one call goes through; a
 * success closes the breaker and a failure opens it again.
 *
 * <p>The state lives in memory, in one Lambda container. A cold start
 * forgets it, which costs at most a few calls to a provider that is
 * still down.
 */
@Component
public class ProviderBreakers {

    private record State(int failures, Instant openUntil) {
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int threshold;
    private final Duration cooldown;

    public ProviderBreakers(Clock clock, ModelProperties properties) {
        this.clock = clock;
        this.threshold = properties.breaker().failures();
        this.cooldown = properties.breaker().cooldown();
    }

    /** Forgets every failure, so every provider is called again. */
    public void reset() {
        states.clear();
    }

    /** Returns why the provider is not to be called now, or empty when it may be. */
    Optional<String> refusal(String provider) {
        State state = states.get(provider);
        if (state == null || state.openUntil() == null
                || !clock.instant().isBefore(state.openUntil())) {
            return Optional.empty();
        }
        return Optional.of("Not called: its last %d calls failed, so it is left alone until %s."
                .formatted(state.failures(), state.openUntil()));
    }

    void succeeded(String provider) {
        states.remove(provider);
    }

    void failed(String provider) {
        states.compute(provider, (name, state) -> {
            int failures = state == null ? 1 : state.failures() + 1;
            Instant openUntil = failures >= threshold ? clock.instant().plus(cooldown) : null;
            return new State(failures, openUntil);
        });
    }
}
