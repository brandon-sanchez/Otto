package otto;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.events.EventLog;
import otto.events.EventType;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class PreDraftScenarioTest extends WireSeamTest {

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private EventLog eventLog;

    private void stubHealthyPreDraft() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.LEAGUE_PATH,
                "sleeper/league-pre-draft.json", "league-v1");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Any phrase");
    }

    @Test
    void preDraftDropsTheCadenceToOneCheckPerDay() {
        stubHealthyPreDraft();
        checkRunner.runCheck();
        sleeper.verify(1, getRequestedFor(urlEqualTo(SleeperStubs.LEAGUE_PATH)));

        // One hour later: the Check skips entirely, no polling.
        clock.advance(Duration.ofHours(1));
        checkRunner.runCheck();
        sleeper.verify(1, getRequestedFor(urlEqualTo(SleeperStubs.LEAGUE_PATH)));
        sleeper.verify(1, getRequestedFor(urlEqualTo(SleeperStubs.PLAYERS_PATH)));

        // Past one day: the Check polls again.
        clock.advance(Duration.ofHours(23).plusSeconds(1));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        checkRunner.runCheck();
        sleeper.verify(1, getRequestedFor(urlEqualTo(SleeperStubs.LEAGUE_PATH)));
    }

    @Test
    void preDraftNeverSendsRosterAlerts() {
        stubHealthyPreDraft();
        checkRunner.runCheck();

        clock.advance(Duration.ofHours(24).plusSeconds(1));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.LEAGUE_PATH, "league-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-mccaffrey-out.json", "players-v2");
        checkRunner.runCheck();

        // The decline is recorded as a Snapshot Diff, but no Alert fires.
        assertThat(eventLog.all())
                .anyMatch(event -> event.type() == EventType.SNAPSHOT_DIFF
                        && event.key().contains("4034"));
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }
}
