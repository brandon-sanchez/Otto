package otto;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.events.Event;
import otto.events.EventLog;
import otto.harness.KickerAndDefenseLeague;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lineup legality keys off legality on every Check, not off a Diff:
 * an illegal lineup found on the very first Check Alerts immediately,
 * once per problem.
 */
class LineupLegalityScenarioTest extends WireSeamTest {

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private EventLog eventLog;

    private void stubIllegalLineup() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-illegal.json", "rosters-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-mccaffrey-out.json", "players-v1");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Your lineup needs attention before Sunday.");
    }

    @Test
    void anIllegalLineupAlertsOnTheVeryFirstCheckWithoutAnyDiff() {
        stubIllegalLineup();

        checkRunner.runCheck();

        // Three distinct problems, three Alerts: a locked-out starter,
        // a bye starter, and an empty slot.
        telegram.verify(3, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.contains("alert:legality:2026-w2:slot1:4034:locked-out")).isTrue();
        assertThat(eventLog.contains("alert:legality:2026-w2:slot4:9493:on-bye")).isTrue();
        assertThat(eventLog.contains("alert:legality:2026-w2:slot6:empty")).isTrue();
    }

    @Test
    void aPlayerOnIrIsNeverPointedToAsTheReplacement() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-reserve.json", "rosters-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-mccaffrey-out.json", "players-v1");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Your lineup needs attention before Sunday.");

        checkRunner.runCheck();

        Event lockedOut = eventLog.all().stream()
                .filter(event -> event.key().equals("alert:legality:2026-w2:slot1:4034:locked-out"))
                .findFirst().orElseThrow();
        assertThat(lockedOut.facts()).containsEntry("replacement", "none on the bench");
    }

    @Test
    void everyLegalityAlertIsHighConfidenceWithAReplacementPointer() {
        stubIllegalLineup();

        checkRunner.runCheck();

        Event byeAlert = eventLog.all().stream()
                .filter(event -> event.key().equals("alert:legality:2026-w2:slot4:9493:on-bye"))
                .findFirst().orElseThrow();
        assertThat(byeAlert.facts())
                .containsEntry("confidence", "HIGH")
                .containsEntry("player", "Puka Nacua")
                .containsEntry("reason", "bye starter")
                // Nacua has no stat line: never a silent zero.
                .containsEntry("projection", "no projection available")
                // The best playable WR on the bench, priced in league scoring.
                .containsEntry("replacement", "CeeDee Lamb (17.0)");

        Event emptyAlert = eventLog.all().stream()
                .filter(event -> event.key().equals("alert:legality:2026-w2:slot6:empty"))
                .findFirst().orElseThrow();
        assertThat(emptyAlert.facts())
                .containsEntry("confidence", "HIGH")
                .containsEntry("slot", "FLEX");
    }

    @Test
    void theSameIllegalLineupNeverAlertsTwice() {
        stubIllegalLineup();
        checkRunner.runCheck();

        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        checkRunner.runCheck();

        telegram.verify(3, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void anEmptyKickerOrDefenseSlotPointsToTheBenchedKickerOrDefense() {
        KickerAndDefenseLeague.stub(sleeper, "0", "0",
                List.of(KickerAndDefenseLeague.KICKER, KickerAndDefenseLeague.DEFENSE));
        stubOutbound();

        checkRunner.runCheck();

        assertThat(emptySlotReplacement(KickerAndDefenseLeague.KICKER_SLOT))
                .isEqualTo(KickerAndDefenseLeague.KICKER + " (9.0)");
        assertThat(emptySlotReplacement(KickerAndDefenseLeague.DEFENSE_SLOT))
                .isEqualTo(KickerAndDefenseLeague.DEFENSE + " (3.0)");
    }

    @Test
    void aBenchedRunningBackIsNeverCoverForAnEmptyKickerOrDefenseSlot() {
        KickerAndDefenseLeague.stub(sleeper, "0", "0", List.of());
        stubOutbound();

        checkRunner.runCheck();

        assertThat(emptySlotReplacement(KickerAndDefenseLeague.KICKER_SLOT))
                .isEqualTo("none on the bench");
        assertThat(emptySlotReplacement(KickerAndDefenseLeague.DEFENSE_SLOT))
                .isEqualTo("none on the bench");
    }

    @Test
    void aStartedKickerAndDefenseAreALegalLineup() {
        KickerAndDefenseLeague.stub(sleeper, KickerAndDefenseLeague.KICKER,
                KickerAndDefenseLeague.DEFENSE, List.of());
        stubOutbound();

        checkRunner.runCheck();

        assertThat(eventLog.all()).noneMatch(event -> event.key().startsWith("alert:legality:"));
    }

    private void stubOutbound() {
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Your lineup needs attention before Sunday.");
    }

    private String emptySlotReplacement(int slot) {
        return eventLog.all().stream()
                .filter(event -> event.key().equals("alert:legality:2026-w2:slot%d:empty"
                        .formatted(slot)))
                .findFirst().orElseThrow()
                .facts().get("replacement");
    }
}
