package otto.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one way Otto asks a model for anything. Providers are tried one
 * at a time in the configured order, never in parallel: a fallback is
 * paid for only when every provider before it failed. A provider is
 * skipped when its call throws (it is down, timed out after its retry
 * budget, or rate-limited), when its reply is unusable, or when its
 * circuit breaker is open.
 *
 * <p>The caller decides what a usable reply is: its {@link ProviderCall}
 * throws {@link UnusableReply} for one it cannot read, and the next
 * provider gets the same call.
 */
public class ModelChain {

    private static final Logger log = LoggerFactory.getLogger(ModelChain.class);

    private final List<ChatProvider> providers;
    private final ProviderBreakers breakers;

    public ModelChain(List<ChatProvider> providers, ProviderBreakers breakers) {
        this.providers = List.copyOf(providers);
        this.breakers = breakers;
    }

    /**
     * Work done against one provider: one or more model calls, and
     * reading what comes back.
     *
     * @param <T> what the work produces
     */
    public interface ProviderCall<T> {

        /**
         * Asks one provider.
         *
         * @throws UnusableReply when the provider answered with nothing
         *         the caller can use; the chain moves on
         */
        T call(ChatProvider provider) throws UnusableReply;

        /** Hears that a provider was skipped without being called. */
        default void passedOver(ChatProvider provider, String reason) {
        }
    }

    /** A reply the caller cannot use, such as an empty or unreadable one. */
    public static final class UnusableReply extends Exception {

        public UnusableReply(String reason) {
            super(reason);
        }
    }

    /**
     * Why the chain moved past one provider.
     *
     * @param reached true when the provider answered, but unusably
     */
    public record Skip(String provider, String model, String reason, boolean reached) {
    }

    /** How a run of the chain ended. */
    public sealed interface Result<T> {

        /** Every provider the chain moved past, in order. */
        List<Skip> skipped();

        /** The first usable answer, from {@code provider}. */
        record Answered<T>(ChatProvider provider, T value, List<Skip> skipped)
                implements Result<T> {

            public Answered {
                skipped = List.copyOf(skipped);
            }
        }

        /** No provider gave a usable answer; empty when none is configured. */
        record Exhausted<T>(List<Skip> skipped) implements Result<T> {

            public Exhausted {
                skipped = List.copyOf(skipped);
            }

            /** True when at least one provider answered, but unusably. */
            public boolean anyReached() {
                return skipped.stream().anyMatch(Skip::reached);
            }

            /** One line naming each provider and why it was skipped. */
            public String reason() {
                if (skipped.isEmpty()) {
                    return "No model provider is configured.";
                }
                return String.join(" ", skipped.stream()
                        .map(skip -> skip.provider() + ": " + skip.reason())
                        .toList());
            }
        }
    }

    /** Runs the call against each provider in turn until one gives a usable answer. */
    public <T> Result<T> call(ProviderCall<T> call) {
        List<Skip> skipped = new ArrayList<>();
        for (ChatProvider provider : providers) {
            Optional<String> refusal = breakers.refusal(provider.name());
            if (refusal.isPresent()) {
                skipped.add(new Skip(provider.name(), provider.model(), refusal.get(), false));
                call.passedOver(provider, refusal.get());
                continue;
            }
            try {
                T value = call.call(provider);
                breakers.succeeded(provider.name());
                return new Result.Answered<>(provider, value, skipped);
            } catch (UnusableReply e) {
                breakers.succeeded(provider.name());
                log.warn("{} answered unusably: {}", provider.name(), e.getMessage());
                skipped.add(new Skip(provider.name(), provider.model(), e.getMessage(), true));
            } catch (RuntimeException e) {
                breakers.failed(provider.name());
                log.warn("{} could not be reached", provider.name(), e);
                skipped.add(new Skip(provider.name(), provider.model(), unreachable(e), false));
                if (interrupted(e)) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return new Result.Exhausted<>(skipped);
    }

    /** The reason recorded for a call that threw. */
    public static String unreachable(RuntimeException e) {
        return "The model could not be reached: " + e.getClass().getSimpleName();
    }

    private static boolean interrupted(RuntimeException e) {
        return e.getCause() instanceof InterruptedException;
    }
}
