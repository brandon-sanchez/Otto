package otto;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import otto.check.CheckRunner;
import otto.check.LastCheckStore;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Check whose shared model budget cannot fit another advice run: the
 * run is not started, nothing records it as made, and the Check still
 * finishes.
 */
class CheckDeadlineScenarioTest extends WireSeamTest {

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private LastCheckStore lastCheck;

    @Autowired
    private OttoProperties properties;

    @DynamicPropertySource
    static void aCheckTooShortForAnyAdviceRun(DynamicPropertyRegistry registry) {
        registry.add("otto.models.budgets.check", () -> "PT1S");
    }

    @Test
    void adviceThatNoLongerFitsTheCheckIsLeftUnmadeForTheNextCheck() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH,
                "sleeper/projections-edge.json", "projections-v1");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmAdvises(llm, "{}");

        checkRunner.runCheck();

        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH)));
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(Path.of(properties.storageDir(), "decision-records")).satisfies(records ->
                assertThat(Files.exists(records.resolve("records"))).isFalse());
        assertThat(lastCheck.read()).hasValueSatisfying(check ->
                assertThat(check.lastCheckAt()).isEqualTo(clock.instant()));
    }
}
