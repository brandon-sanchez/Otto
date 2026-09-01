package otto;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.directory.DirectoryPlayer;
import otto.directory.PlayerDirectory;
import otto.directory.PlayerDirectoryStore;
import otto.directory.PlayerHealth;
import otto.directory.PlayerLookup;
import otto.harness.WireSeamTest;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class PlayerLookupScenarioTest extends WireSeamTest {

    @Autowired
    private PlayerDirectoryStore directoryStore;

    @Autowired
    private PlayerLookup lookup;

    @Test
    void punctuationVariantsResolveTheSamePlayer() {
        DirectoryPlayer chase = new DirectoryPlayer(
                "7564", "Ja'Marr Chase", "WR", "CIN", PlayerHealth.ACTIVE);
        directoryStore.write(new PlayerDirectory(
                "players-v1", Instant.parse("2026-09-15T17:00:00Z"),
                Map.of(chase.playerId(), chase)));

        assertThat(lookup.find("Ja’Marr Chase"))
                .isEqualTo(new PlayerLookup.Match.Found(chase));
        assertThat(lookup.find("JaMarr Chase"))
                .isEqualTo(new PlayerLookup.Match.Found(chase));
    }

    @Test
    void anAmbiguousNameDoesNotDownloadTheDirectoryAgain() {
        DirectoryPlayer quarterback = new DirectoryPlayer(
                "1", "Josh Allen", "QB", "BUF", PlayerHealth.ACTIVE);
        DirectoryPlayer defender = new DirectoryPlayer(
                "2", "Josh Allen", "TE", "FA", PlayerHealth.ACTIVE);
        directoryStore.write(new PlayerDirectory(
                "players-v1", Instant.parse("2026-09-15T17:00:00Z"),
                Map.of(quarterback.playerId(), quarterback,
                        defender.playerId(), defender)));

        assertThat(lookup.findFresh("Josh Allen"))
                .isInstanceOf(PlayerLookup.Match.NotFound.class);
        sleeper.verify(0, getRequestedFor(urlEqualTo("/v1/players/nfl")));
    }
}
