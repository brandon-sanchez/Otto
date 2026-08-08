package otto;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.directory.PlayerDirectory;
import otto.directory.PlayerDirectoryStore;
import otto.directory.PlayerHealth;
import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;
import otto.harness.Fixtures;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class PlayerDirectoryScenarioTest extends WireSeamTest {

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private PlayerDirectoryStore directoryStore;

    @Autowired
    private EventLog eventLog;

    private void stubPlayersFile(String fixture, String etag) {
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH, fixture, etag);
    }

    @Test
    void theSixtySecondEtagGateAvoidsRepeatDownloads() {
        stubPlayersFile("sleeper/players-nfl.json", "players-v1");
        checkRunner.runCheck();

        clock.advance(Duration.ofSeconds(30));
        checkRunner.runCheck();
        sleeper.verify(1, getRequestedFor(urlEqualTo("/v1/players/nfl")));

        sleeper.resetAll();
        sleeper.stubFor(get(urlEqualTo("/v1/players/nfl"))
                .withHeader("If-None-Match", equalTo("\"players-v1\""))
                .willReturn(aResponse().withStatus(304)));
        clock.advance(Duration.ofSeconds(31));
        checkRunner.runCheck();

        sleeper.verify(1, getRequestedFor(urlEqualTo("/v1/players/nfl"))
                .withHeader("If-None-Match", equalTo("\"players-v1\"")));
        PlayerDirectory directory = directoryStore.read().orElseThrow();
        assertThat(directory.players()).containsKey("4034");
    }

    @Test
    void aPlayersFileChangeUpdatesTheDirectoryAndEmitsStatusEvents() {
        stubPlayersFile("sleeper/players-nfl.json", "players-v1");
        checkRunner.runCheck();

        clock.advance(Duration.ofSeconds(61));
        stubPlayersFile("sleeper/players-nfl-mccaffrey-out.json", "players-v2");
        checkRunner.runCheck();

        PlayerDirectory directory = directoryStore.read().orElseThrow();
        assertThat(directory.players().get("4034").health()).isEqualTo(PlayerHealth.OUT);
        assertThat(directory.etag()).isEqualTo("\"players-v2\"");

        Event statusEvent = eventLog.all().stream()
                .filter(event -> event.type() == EventType.PLAYER_STATUS_CHANGE)
                .filter(event -> event.key().contains("4034"))
                .findFirst().orElseThrow();
        assertThat(statusEvent.facts())
                .containsEntry("player", "Christian McCaffrey")
                .containsEntry("from", "ACTIVE")
                .containsEntry("to", "OUT");
    }

    @Test
    void everySleeperDesignationMapsToAHealth() {
        stubPlayersFile("sleeper/players-nfl.json", "players-v1");
        checkRunner.runCheck();

        PlayerDirectory directory = directoryStore.read().orElseThrow();
        // injury_status designations.
        assertThat(directory.players().get("8135").health()).isEqualTo(PlayerHealth.PROBABLE);
        assertThat(directory.players().get("2216").health()).isEqualTo(PlayerHealth.SUSPENDED);
        // status designations with no injury_status.
        assertThat(directory.players().get("5859").health()).isEqualTo(PlayerHealth.PUP);
        assertThat(directory.players().get("4199").health()).isEqualTo(PlayerHealth.NOT_ACTIVE);
        // Both fields set: the worse designation wins.
        assertThat(directory.players().get("4029").health()).isEqualTo(PlayerHealth.IR);
        // A designation this code does not know yet stays visible as UNKNOWN.
        assertThat(directory.players().get("6768").health()).isEqualTo(PlayerHealth.UNKNOWN);
    }

    @Test
    void aCheckBuildsThePlayerDirectoryTrimmedToTheFourPositions() {
        sleeper.stubFor(get(urlEqualTo("/v1/players/nfl")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withHeader("ETag", "\"players-v1\"")
                .withBody(Fixtures.read("sleeper/players-nfl.json"))));

        checkRunner.runCheck();

        PlayerDirectory directory = directoryStore.read().orElseThrow();
        assertThat(directory.players())
                .containsKeys("4046", "4034", "6794", "1466", "4866", "8135");
        assertThat(directory.players()).doesNotContainKeys("3678", "KC");
        assertThat(directory.players().get("4034").fullName()).isEqualTo("Christian McCaffrey");
        sleeper.verify(1, getRequestedFor(urlEqualTo("/v1/players/nfl")));
    }
}
