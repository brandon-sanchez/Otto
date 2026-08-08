package otto;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.events.Event;
import otto.events.EventLog;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A bench player who out-projects a starter by at least the edge
 * threshold (default 1.0) in a legal slot produces a Recommendation.
 * All points compute in Java as stat line times league scoring;
 * Sleeper's pre-computed points are poison in the fixtures and must
 * never surface.
 */
class EdgeScenarioTest extends WireSeamTest {

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private EventLog eventLog;

    private void stubWithProjections(String projectionsFixture) {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH,
                projectionsFixture, "projections-v1");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Jacobs projects better than Cook this week.");
    }

    @Test
    void aBenchPlayerOverTheThresholdSendsOneMediumEdgeAlert() {
        stubWithProjections("sleeper/projections-edge.json");

        checkRunner.runCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        Event alert = eventLog.all().stream()
                .filter(event -> event.key().equals("alert:edge:2026-w2:5850-over-8138"))
                .findFirst().orElseThrow();
        assertThat(alert.facts())
                .containsEntry("confidence", "MEDIUM")
                .containsEntry("bench", "Josh Jacobs")
                .containsEntry("starter", "James Cook")
                .containsEntry("benchProjection", "16.5")
                .containsEntry("starterProjection", "13.0")
                .containsEntry("edge", "3.5");

        // The numbers handed to the LLM are Java-computed league
        // scoring, never Sleeper's pre-computed pts_ppr poison.
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("16.5"))
                .withRequestBody(containing("13.0"))
                .withRequestBody(notContaining("99.9")));

        // The same edge never alerts twice.
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        checkRunner.runCheck();
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void anEdgeBelowTheThresholdStaysSilent() {
        stubWithProjections("sleeper/projections-small-edge.json");

        checkRunner.runCheck();

        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void theTeReceptionPremiumCountsInTheProjectionMath() {
        stubWithProjections("sleeper/projections-te-edge.json");

        checkRunner.runCheck();

        // Goedert: 6 receptions at 1.5 (full PPR plus TE premium) plus
        // 40 yards at 0.1 = 13.0. Without the premium he would project
        // 10.0 and no edge over Kelce (11.0) would exist at all.
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        Event alert = eventLog.all().stream()
                .filter(event -> event.key().equals("alert:edge:2026-w2:6813-over-1466"))
                .findFirst().orElseThrow();
        assertThat(alert.facts())
                .containsEntry("benchProjection", "13.0")
                .containsEntry("starterProjection", "11.0")
                .containsEntry("edge", "2.0")
                .containsEntry("slot", "TE");
    }

    @Test
    void theOptimizerIsSuperflexAware() {
        stubWithProjections("sleeper/projections-superflex.json");

        checkRunner.runCheck();

        // Love (QB) drops to 9.0, so the optimal SUPER_FLEX holds the
        // best remaining player of any position: Jacobs (RB, 12.5).
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        Event alert = eventLog.all().stream()
                .filter(event -> event.key().equals("alert:edge:2026-w2:5850-over-8135"))
                .findFirst().orElseThrow();
        assertThat(alert.facts())
                .containsEntry("bench", "Josh Jacobs")
                .containsEntry("starter", "Jordan Love")
                .containsEntry("edge", "3.5")
                .containsEntry("slot", "SUPER_FLEX");
    }
}
