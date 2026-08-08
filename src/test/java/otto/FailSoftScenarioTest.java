package otto;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.directory.PlayerDirectoryStore;
import otto.events.EventLog;
import otto.events.EventType;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class FailSoftScenarioTest extends WireSeamTest {

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private PlayerDirectoryStore directoryStore;

    @Autowired
    private EventLog eventLog;

    @Test
    void aBrokenFeedSelfReportsOnceWhileTheRestOfTheCheckKeepsRunning() {
        SleeperStubs.healthyInSeason(sleeper);
        OutboundStubs.telegramOk(telegram);
        checkRunner.runCheck();

        // Sleeper's rosters feed breaks; everything else stays healthy.
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        sleeper.stubFor(get(urlEqualTo(SleeperStubs.ROSTERS_PATH))
                .willReturn(aResponse().withStatus(500)));
        checkRunner.runCheck();

        // Typed source-unavailable result recorded, one self-report Alert sent.
        assertThat(eventLog.all())
                .anyMatch(event -> event.type() == EventType.SOURCE_UNAVAILABLE
                        && event.key().contains("rosters"));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("rosters")));

        // The unaffected Player Directory work kept running on this Check.
        assertThat(directoryStore.read().orElseThrow().checkedAt())
                .isEqualTo(TEST_START.plusSeconds(61));

        // The same broken feed never self-reports twice.
        clock.advance(Duration.ofSeconds(61));
        checkRunner.runCheck();
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }
}
