package otto.model;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

/**
 * One circuit breaker per model provider. A provider whose calls failed
 * {@link ModelProperties.Breaker#failures()} times in a row is left
 * alone for the cooldown, so a provider that is down is not called on
 * every one-minute Check. After the cooldown one trial call goes
 * through and every other call waits another cooldown behind it; a
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

    /**
     * Returns why the provider is not to be called now, or empty when it
     * may be. Only looks: a provider whose cooldown is over is not
     * claimed for its trial call; {@link #admit} does that.
     */
    Optional<String> refusal(String provider) {
        State state = states.get(provider);
        if (state == null || !open(state)) {
            return Optional.empty();
        }
        return Optional.of(refusalOf(state));
    }

    /**
     * Returns why the provider is not to be called now, or empty when the
     * caller may call it. A caller let through once the cooldown is over
     * holds the one trial call: the breaker stays open for everyone else
     * for another cooldown, until {@link #succeeded} or {@link #failed}.
     */
    Optional<String> admit(String provider) {
        Instant now = clock.instant();
        AtomicReference<State> refused = new AtomicReference<>();
        states.computeIfPresent(provider, (name, state) -> {
            if (state.openUntil() == null) {
                return state;
            }
            if (now.isBefore(state.openUntil())) {
                refused.set(state);
                return state;
            }
            return new State(state.failures(), now.plus(cooldown));
        });
        return Optional.ofNullable(refused.get()).map(ProviderBreakers::refusalOf);
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

    private boolean open(State state) {
        return state.openUntil() != null && clock.instant().isBefore(state.openUntil());
    }

    private static String refusalOf(State state) {
        return "Not called: its last %d calls failed, so it is left alone until %s."
                .formatted(state.failures(), state.openUntil());
    }
}
