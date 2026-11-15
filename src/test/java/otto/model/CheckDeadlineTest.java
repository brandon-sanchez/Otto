package otto.model;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import otto.harness.MutableClock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckDeadlineTest {

    private static final Duration ADVICE_DEADLINE = Duration.ofMillis(400);

    private final ModelProperties.Budget advice =
            new ModelProperties.Budget(ADVICE_DEADLINE, Duration.ofMillis(100));
    private final ModelProperties.Budgets budgets =
            new ModelProperties.Budgets(advice, advice, advice, Duration.ofMillis(600));
    private final ProviderBreakers breakers = new ProviderBreakers(
            new MutableClock(Instant.parse("2026-09-15T17:00:00Z"), ZoneOffset.UTC),
            new ModelProperties(List.of("openai"), new ModelProperties.Breaker(3,
                    Duration.ofMinutes(5)), budgets, null, null));
    private final ModelChain chain = new ModelChain(
            List.of(new ChatProvider("openai", "m", null)), breakers, budgets);

    @Test
    void aRunWhoseWholeBudgetNoLongerFitsTheCheckIsNotStarted() {
        AtomicInteger calls = new AtomicInteger();

        chain.withinCheck(() -> {
            assertThat(chain.call(ModelChain.CallSite.ADVICE, turn -> slowAnswer(calls)))
                    .isInstanceOf(ModelChain.Result.Answered.class);
            assertThatThrownBy(() -> chain.call(ModelChain.CallSite.ADVICE,
                    turn -> slowAnswer(calls)))
                    .isInstanceOf(ModelChain.OutOfCheckTime.class);
            return null;
        });

        assertThat(calls).hasValue(1);
    }

    @Test
    void aRunOutsideACheckHasItsOwnBudgetWhateverRanBefore() {
        AtomicInteger calls = new AtomicInteger();

        chain.call(ModelChain.CallSite.ADVICE, turn -> slowAnswer(calls));
        chain.call(ModelChain.CallSite.ADVICE, turn -> slowAnswer(calls));

        assertThat(calls).hasValue(2);
    }

    @Test
    void eachCheckStartsWithAFullBudget() {
        AtomicInteger calls = new AtomicInteger();

        chain.withinCheck(() -> chain.call(ModelChain.CallSite.ADVICE,
                turn -> slowAnswer(calls)));
        chain.withinCheck(() -> chain.call(ModelChain.CallSite.ADVICE,
                turn -> slowAnswer(calls)));

        assertThat(calls).hasValue(2);
    }

    private static String slowAnswer(AtomicInteger calls) {
        calls.incrementAndGet();
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "answer";
    }
}
