package otto.model;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import otto.harness.MutableClock;
import otto.scope.LeagueScope;

import static org.assertj.core.api.Assertions.assertThat;

class LeagueHopTest {

    private final ModelProperties.Budget budget =
            new ModelProperties.Budget(Duration.ofSeconds(5), Duration.ofSeconds(1));
    private final ModelProperties.Budgets budgets =
            new ModelProperties.Budgets(budget, budget, budget, Duration.ofSeconds(30));
    private final ModelChain chain = new ModelChain(
            List.of(new ChatProvider("openai", "m", null)),
            new ProviderBreakers(
                    new MutableClock(Instant.parse("2026-09-15T17:00:00Z"), ZoneOffset.UTC),
                    new ModelProperties(List.of("openai"),
                            new ModelProperties.Breaker(3, Duration.ofMinutes(5)), budgets,
                            null, null)),
            budgets);

    @Test
    void aModelCallRunsForTheLeagueItsCallerWasWorkingFor() {
        ModelChain.Result<Optional<String>> result = LeagueScope.call("2001",
                () -> chain.call(ModelChain.CallSite.ASK,
                        turn -> turn.timed(LeagueScope::bound)));

        assertThat(result).isInstanceOfSatisfying(ModelChain.Result.Answered.class,
                answered -> assertThat(answered.value()).isEqualTo(Optional.of("2001")));
    }

    @Test
    void aModelCallOutsideAnyLeagueStaysOutsideOne() {
        ModelChain.Result<Optional<String>> result = chain.call(ModelChain.CallSite.ASK,
                turn -> turn.timed(LeagueScope::bound));

        assertThat(result).isInstanceOfSatisfying(ModelChain.Result.Answered.class,
                answered -> assertThat(answered.value()).isEqualTo(Optional.empty()));
    }
}
