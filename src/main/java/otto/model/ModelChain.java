package otto.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one way Otto asks a model for anything. Providers are tried one
 * at a time in the configured order, never in parallel: a fallback is
 * paid for only when every provider before it failed. A provider is
 * skipped when its call throws (it is down, ran out of time, or is
 * rate-limited), when its reply is unusable, or when its circuit
 * breaker is open.
 *
 * <p>The caller decides what a usable reply is: its {@link ProviderCall}
 * throws {@link UnusableReply} for one it cannot read, and the next
 * provider gets the same call.
 *
 * <p>Each run has one deadline, set by its {@link CallSite}. A provider
 * gets the time left minus a reserve for each later provider that may
 * still be asked, so one that hangs cannot use up the whole run.
 *
 * <p>The runs of one Check also share {@link ModelProperties.Budgets#check()},
 * so that several Alerts in one Check cannot outlast the scheduled
 * function's timeout. A run starts only while its whole deadline still
 * fits in what the Check has left. Past that, {@link #call} throws
 * {@link OutOfCheckTime} before asking anyone, and the run is left for
 * the next Check.
 */
public class ModelChain {

    private static final Logger log = LoggerFactory.getLogger(ModelChain.class);

    private final List<ChatProvider> providers;
    private final ProviderBreakers breakers;
    private final ModelProperties.Budgets budgets;

    private final ExecutorService abandonableCalls = Executors.newVirtualThreadPerTaskExecutor();

    /** When the Check this thread runs must end, in {@link System#nanoTime} terms. */
    private static final ScopedValue<Long> CHECK_ENDS = ScopedValue.newInstance();

    public ModelChain(List<ChatProvider> providers, ProviderBreakers breakers,
            ModelProperties.Budgets budgets) {
        this.providers = List.copyOf(providers);
        this.breakers = breakers;
        this.budgets = budgets;
    }

    /** Where a run of the chain comes from, which sets its time budget. */
    public enum CallSite {
        /** A chat reply; see {@link ModelProperties.Budgets#ask()}. */
        ASK,
        /** An Alert's wording; see {@link ModelProperties.Budgets#phrasing()}. */
        PHRASING
    }

    /**
     * Work done against one provider: one or more model calls, and
     * reading what comes back.
     *
     * @param <T> what the work produces
     */
    public interface ProviderCall<T> {

        /**
         * Asks one provider. Each model call goes through
         * {@link Turn#timed}, so it stops when the turn's time is up.
         *
         * @throws UnusableReply when the provider answered with nothing
         *         the caller can use; the chain moves on
         */
        T call(Turn turn) throws UnusableReply;

        /** Hears that a provider was skipped without being called. */
        default void passedOver(ChatProvider provider, String reason) {
        }
    }

    /** One provider's turn in a run, and the time it has. */
    public final class Turn {

        private final ChatProvider provider;
        private final long untilNanos;
        private final boolean laterProviderRemains;

        private Turn(ChatProvider provider, long untilNanos, boolean laterProviderRemains) {
            this.provider = provider;
            this.untilNanos = untilNanos;
            this.laterProviderRemains = laterProviderRemains;
        }

        /** Returns the provider whose turn this is. */
        public ChatProvider provider() {
            return provider;
        }

        /** True when a later provider may still be asked if this one is skipped. */
        public boolean laterProviderRemains() {
            return laterProviderRemains;
        }

        /**
         * Runs one blocking model call within this turn's time. A call
         * still running when the time is up is abandoned: its thread is
         * interrupted and its answer, if one comes, is dropped.
         *
         * @throws ModelTimeout when the turn's time runs out first
         */
        public <R> R timed(Supplier<R> call) {
            long remaining = untilNanos - System.nanoTime();
            if (remaining <= 0) {
                throw new ModelTimeout();
            }
            Future<R> future = abandonableCalls.submit(call::get);
            try {
                return future.get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new ModelTimeout();
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for " + provider.name(), e);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException failure) {
                    throw failure;
                }
                if (e.getCause() instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException(e.getCause());
            }
        }
    }

    /**
     * A run not started because the Check it belongs to has too little
     * time left for it. The next Check makes the run.
     */
    public static final class OutOfCheckTime extends RuntimeException {

        OutOfCheckTime() {
            super("The Check has too little time left to start this model run.");
        }
    }

    /** A model call that did not answer within its turn's share of the deadline. */
    public static final class ModelTimeout extends RuntimeException {

        ModelTimeout() {
            super("The model did not answer in the time it had.");
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

    /**
     * Runs {@code check} as one Check: every model run it makes shares
     * {@link ModelProperties.Budgets#check()}.
     */
    public <T> T withinCheck(Supplier<T> check) {
        return ScopedValue.where(CHECK_ENDS, System.nanoTime() + budgets.check().toNanos())
                .call(check::get);
    }

    /**
     * Runs the call against each provider in turn until one gives a
     * usable answer or the site's deadline passes.
     *
     * @throws OutOfCheckTime inside {@link #withinCheck}, when the site's
     *         deadline no longer fits in what the Check has left; no
     *         provider is asked
     */
    public <T> Result<T> call(CallSite site, ProviderCall<T> call) {
        ModelProperties.Budget budget = budget(site);
        long deadline = System.nanoTime() + budget.deadline().toNanos();
        if (CHECK_ENDS.isBound() && CHECK_ENDS.get() - deadline < 0) {
            throw new OutOfCheckTime();
        }
        List<Skip> skipped = new ArrayList<>();
        for (int index = 0; index < providers.size(); index++) {
            ChatProvider provider = providers.get(index);
            long later = providers.subList(index + 1, providers.size()).stream()
                    .filter(next -> breakers.refusal(next.name()).isEmpty())
                    .count();
            long until = deadline - later * budget.reserve().toNanos();
            Optional<String> refusal = until > System.nanoTime()
                    ? breakers.admit(provider.name())
                    : Optional.of("Not called: no time was left for it.");
            if (refusal.isPresent()) {
                skipped.add(new Skip(provider.name(), provider.model(), refusal.get(), false));
                call.passedOver(provider, refusal.get());
                continue;
            }
            try {
                T value = call.call(new Turn(provider, until, later > 0));
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
        if (e instanceof ModelTimeout) {
            return e.getMessage();
        }
        return "The model could not be reached: " + e.getClass().getSimpleName();
    }

    private ModelProperties.Budget budget(CallSite site) {
        return switch (site) {
            case ASK -> budgets.ask();
            case PHRASING -> budgets.phrasing();
        };
    }

    private static boolean interrupted(RuntimeException e) {
        return e.getCause() instanceof InterruptedException;
    }
}
