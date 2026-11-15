package otto;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.delivery.OutboxFaults;
import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;
import otto.harness.AdviceStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.storage.JsonStore;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

class AlertScenarioTest extends WireSeamTest {

    private static final String PHRASE =
            "McCaffrey is Out for Sunday. Bench him and start a healthy RB.";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private EventLog eventLog;

    @Autowired
    private JsonStore store;

    private void runHealthyBaselineCheck() {
        SleeperStubs.healthyInSeason(sleeper);
        OutboundStubs.telegramOk(telegram);
        modelAdvisesOnTheDecline();
        checkRunner.runCheck();
    }

    private void modelAdvisesOnTheDecline() {
        OutboundStubs.llmPhrases(llm, PHRASE);
        AdviceStubs.llmDecidesOn(llm, "Out", AdviceStubs.MCCAFFREY_OUT_HOLD);
    }

    private void runDeclineCheck() {
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-mccaffrey-out.json", "players-v2");
        checkRunner.runCheck();
    }

    @Test
    void aStarterStatusDeclineSendsOneCheckedDecisionAlert() {
        runHealthyBaselineCheck();
        runDeclineCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.chat_id", equalTo("4242")))
                .withRequestBody(matchingJsonPath("$.text",
                        equalTo(AdviceStubs.MCCAFFREY_OUT_HOLD_MESSAGE))));

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Christian McCaffrey"))
                .withRequestBody(containing("rosterPlans")));

        Event alert = eventLog.all().stream()
                .filter(event -> event.type() == EventType.ALERT_SENT)
                .filter(event -> event.key().contains("snapshot-diff"))
                .findFirst().orElseThrow();
        assertThat(alert.key()).isEqualTo("alert:snapshot-diff:status:4034:ACTIVE->OUT");
        assertThat(alert.facts())
                .containsEntry("player", "Christian McCaffrey")
                .containsEntry("confidence", "HIGH")
                .containsEntry("action", "Christian McCaffrey went from Active to Out")
                .containsKey("runId");
    }

    @Test
    void aQuestionablePlayerStillOnIrGetsAnEligibilityAlert() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-reserve.json", "rosters-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-jacobs-ir.json", "players-v1");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Baseline.");
        checkRunner.runCheck();

        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-jacobs-questionable.json", "players-v2");
        String message = "Move Josh Jacobs off IR into your open roster spot. Sleeper does not "
                + "let a Questionable player stay on IR in this league.";
        AdviceStubs.llmDecidesOn(llm, "Questionable", AdviceStubs.decision(message, "activate",
                "null", List.of("5850"), List.of("unknown-recovery")));
        checkRunner.runCheck();

        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Josh Jacobs"))
                .withRequestBody(containing("\\\"activate\\\""))
                .withRequestBody(containing("the league does not allow Questionable players "
                        + "on IR")));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(message))));
    }

    @Test
    void aHealthyPlayerStillOnIrGetsAnActionNeededAlert() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-reserve.json", "rosters-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-jacobs-ir.json", "players-v1");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Baseline.");
        checkRunner.runCheck();

        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl.json", "players-v2");
        String message = "Move Josh Jacobs off IR now: he is Active again, and Sleeper does "
                + "not let an Active player stay on IR.";
        AdviceStubs.llmDecidesOn(llm, "Active", AdviceStubs.decision(message, "activate",
                "null", List.of("5850"), List.of()));
        checkRunner.runCheck();

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Move Josh Jacobs off IR into the open roster spot."))
                .withRequestBody(containing("the league does not allow Active players on IR")));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(message))));
        assertThat(eventLog.all()).anySatisfy(event -> {
            assertThat(event.key()).isEqualTo("alert:snapshot-diff:status:5850:IR->ACTIVE");
            assertThat(event.facts()).containsEntry("confidence", "HIGH");
        });
    }

    @Test
    void anEligiblePlayerStillOnIrGetsOneReminderBeforeHisGameLocks() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.ROSTERS_PATH,
                "sleeper/rosters-reserve.json", "rosters-v1");
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-jacobs-ir.json", "players-v1");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Baseline.");
        checkRunner.runCheck();

        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-jacobs-questionable.json", "players-v2");
        OutboundStubs.llmPhrases(llm, "Jacobs is eligible.");
        checkRunner.runCheck();

        telegram.resetRequests();
        llm.resetAll();
        clock.set(Instant.parse("2026-09-21T23:50:00Z"));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.PLAYERS_PATH, "players-v2");
        OutboundStubs.llmPhrases(llm, "IR reminder.");
        checkRunner.runCheck();
        checkRunner.runCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("IR reminder"))
                .withRequestBody(containing("Josh Jacobs")));
    }

    @Test
    void aTransientTelegramFailureRetriesTheAlertOnTheNextCheck() {
        SleeperStubs.healthyInSeason(sleeper);
        modelAdvisesOnTheDecline();
        telegram.stubFor(com.github.tomakehurst.wiremock.client.WireMock
                .post(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock
                        .aResponse().withStatus(500)));
        checkRunner.runCheck();
        runDeclineCheck();
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));

        // Telegram recovers; the next Check delivers the same Alert once.
        telegram.resetAll();
        OutboundStubs.telegramOk(telegram);
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.PLAYERS_PATH, "players-v2");
        checkRunner.runCheck();
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(AdviceStubs.MCCAFFREY_OUT_HOLD_MESSAGE)))
                .withRequestBody(matchingJsonPath(
                        "$.reply_markup.inline_keyboard[0][0].callback_data",
                        equalTo("done:1"))));

        // And never again after that.
        clock.advance(Duration.ofSeconds(61));
        checkRunner.runCheck();
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void aSentAlertDoesNotResendWhenItsEventLogWriteWasLost() {
        runHealthyBaselineCheck();
        runDeclineCheck();

        store.write("event-log", eventLog.all().stream()
                .filter(event -> event.type() != EventType.ALERT_SENT)
                .toList());

        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.PLAYERS_PATH, "players-v2");
        checkRunner.runCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.all()).anyMatch(event ->
                event.key().equals("alert:snapshot-diff:status:4034:ACTIVE->OUT"));
    }

    @Test
    void aPartlyStoredMergedDeliveryRepairsEveryKeyBeforeRetrying() {
        SleeperStubs.healthyInSeason(sleeper);
        modelAdvisesOnTheDecline();
        telegram.stubFor(com.github.tomakehurst.wiremock.client.WireMock
                .post(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock
                        .aResponse().withStatus(500)));
        checkRunner.runCheck();
        runDeclineCheck();

        String legalityKey = "alert:legality:2026-w2:slot1:4034:locked-out";
        OutboxFaults.pointKeyAtMissingDelivery(store, legalityKey);

        telegram.resetAll();
        OutboundStubs.telegramOk(telegram);
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.PLAYERS_PATH, "players-v2");
        checkRunner.runCheck();

        store.write("event-log", eventLog.all().stream()
                .filter(event -> event.type() != EventType.ALERT_SENT)
                .toList());
        clock.advance(Duration.ofHours(4));
        checkRunner.runCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void theSameDeclineNeverAlertsTwice() {
        runHealthyBaselineCheck();
        runDeclineCheck();

        // The player recovers: that transition is its own Alert.
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl.json", "players-v3");
        OutboundStubs.llmPhrases(llm, "McCaffrey is back to Active.");
        checkRunner.runCheck();

        // Then he declines the same way again: no second decline Alert.
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-mccaffrey-out.json", "players-v4");
        checkRunner.runCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(AdviceStubs.MCCAFFREY_OUT_HOLD_MESSAGE))));
        telegram.verify(2, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

}
