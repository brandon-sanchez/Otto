package otto.model;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import otto.harness.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderBreakersTest {

    private static final Duration COOLDOWN = Duration.ofMinutes(5);

    private final MutableClock clock = new MutableClock(
            Instant.parse("2026-09-15T17:00:00Z"), ZoneOffset.UTC);
    private final ModelProperties.Budget budget =
            new ModelProperties.Budget(Duration.ofSeconds(10), Duration.ofSeconds(1));
    private final ProviderBreakers breakers = new ProviderBreakers(clock, new ModelProperties(
            List.of("down"), new ModelProperties.Breaker(3, COOLDOWN),
            new ModelProperties.Budgets(budget, budget, budget, Duration.ofMinutes(4)), null,
            null));
    private final ModelChain chain = new ModelChain(
            List.of(new ChatProvider("down", "m", null)), breakers,
            new ModelProperties.Budgets(budget, budget, budget, Duration.ofMinutes(4)));

    @Test
    void onlyOneTrialCallReachesAProviderWhenItsCooldownEnds() throws Exception {
        for (int failure = 0; failure < 3; failure++) {
            chain.call(ModelChain.CallSite.ADVICE, turn -> {
                throw new IllegalStateException("down");
            });
        }
        clock.advance(COOLDOWN);

        CountDownLatch trialStarted = new CountDownLatch(1);
        CountDownLatch trialMayEnd = new CountDownLatch(1);
        AtomicInteger reached = new AtomicInteger();
        CompletableFuture<ModelChain.Result<String>> trial = CompletableFuture.supplyAsync(
                () -> chain.call(ModelChain.CallSite.ADVICE, turn -> {
                    reached.incrementAndGet();
                    trialStarted.countDown();
                    await(trialMayEnd);
                    return "back";
                }));
        assertThat(trialStarted.await(5, TimeUnit.SECONDS)).isTrue();

        ModelChain.Result<String> during = chain.call(ModelChain.CallSite.ADVICE, turn -> {
            reached.incrementAndGet();
            return "second";
        });
        trialMayEnd.countDown();

        assertThat(during).isInstanceOf(ModelChain.Result.Exhausted.class);
        assertThat(trial.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ModelChain.Result.Answered.class);
        assertThat(reached).hasValue(1);
        assertThat(chain.call(ModelChain.CallSite.ADVICE, turn -> "closed"))
                .isInstanceOf(ModelChain.Result.Answered.class);
    }

    @Test
    void aFailedTrialLeavesTheProviderAloneForAnotherCooldown() {
        for (int failure = 0; failure < 3; failure++) {
            chain.call(ModelChain.CallSite.ADVICE, turn -> {
                throw new IllegalStateException("down");
            });
        }
        clock.advance(COOLDOWN);
        AtomicInteger reached = new AtomicInteger();

        chain.call(ModelChain.CallSite.ADVICE, turn -> {
            reached.incrementAndGet();
            throw new IllegalStateException("still down");
        });
        clock.advance(COOLDOWN.minusSeconds(1));
        chain.call(ModelChain.CallSite.ADVICE, turn -> {
            reached.incrementAndGet();
            return "too soon";
        });

        assertThat(reached).hasValue(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
