package otto;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.directory.PlayerHealth;
import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.snapshot.LeagueStatus;
import otto.snapshot.RosterSnapshot;
import otto.snapshot.Snapshot;
import otto.snapshot.SnapshotStore;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class SnapshotScenarioTest extends WireSeamTest {

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private SnapshotStore snapshotStore;

    @Autowired
    private EventLog eventLog;

    @Test
    void aCheckBuildsAndStoresTheSnapshot() {
        SleeperStubs.healthyInSeason(sleeper);

        checkRunner.runCheck();

        Snapshot current = snapshotStore.current().orElseThrow();
        assertThat(current.at()).isEqualTo(TEST_START);
        assertThat(current.leagueStatus()).isEqualTo(LeagueStatus.IN_SEASON);
        RosterSnapshot mine = current.rosters().stream()
                .filter(RosterSnapshot::userRoster)
                .findFirst().orElseThrow();
        assertThat(mine.ownerName()).isEqualTo("SenorMustache");
        assertThat(mine.starters()).containsExactly("4046", "4034", "6794", "1466");
        assertThat(mine.playerHealth().get("4034")).isEqualTo(PlayerHealth.ACTIVE);
    }

    @Test
    void aSecondCheckPollsWithConditionalGetsAndKeepsThePreviousSnapshot() {
        SleeperStubs.healthyInSeason(sleeper);
        checkRunner.runCheck();

        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        checkRunner.runCheck();

        sleeper.verify(getRequestedFor(urlEqualTo(SleeperStubs.LEAGUE_PATH))
                .withHeader("If-None-Match", equalTo("\"league-v1\"")));
        sleeper.verify(getRequestedFor(urlEqualTo(SleeperStubs.ROSTERS_PATH))
                .withHeader("If-None-Match", equalTo("\"rosters-v1\"")));

        Snapshot current = snapshotStore.current().orElseThrow();
        Snapshot previous = snapshotStore.previous().orElseThrow();
        assertThat(current.at()).isEqualTo(TEST_START.plusSeconds(61));
        assertThat(previous.at()).isEqualTo(TEST_START);
        assertThat(eventLog.all())
                .noneMatch(event -> event.type() == EventType.SNAPSHOT_DIFF);
    }

    @Test
    void aRosteredStarterDeclineWritesASnapshotDiffEventToTheEventLog() {
        SleeperStubs.healthyInSeason(sleeper);
        checkRunner.runCheck();

        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-mccaffrey-out.json", "players-v2");
        checkRunner.runCheck();

        Event diffEvent = eventLog.all().stream()
                .filter(event -> event.type() == EventType.SNAPSHOT_DIFF)
                .findFirst().orElseThrow();
        assertThat(diffEvent.key()).isEqualTo("snapshot-diff:status:4034:ACTIVE->OUT");
        assertThat(diffEvent.facts())
                .containsEntry("player", "Christian McCaffrey")
                .containsEntry("from", "ACTIVE")
                .containsEntry("to", "OUT")
                .containsEntry("starter", "true")
                .containsEntry("userRoster", "true");
    }
}
