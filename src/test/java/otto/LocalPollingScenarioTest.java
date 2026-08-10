package otto;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import otto.check.CheckRunner;
import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.storage.JsonStore;
import otto.telegram.LocalTelegramLoop;
import otto.telegram.TelegramWebhook;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The local front door. A laptop has no address Telegram can post to,
 * so under the local profile Otto asks Telegram for its own updates and
 * hands each one to the webhook seam the deployed function URL calls.
 * The driver is built here by hand rather than by the profile, so a
 * poll happens when the test says so and never on a thread of its own.
 */
class LocalPollingScenarioTest extends WireSeamTest {

    private static final String ANSWER = "Start Jacobs over Cook: +3.5 points.";

    @Autowired
    private OttoProperties properties;

    @Autowired
    private TelegramWebhook webhook;

    @Autowired
    private JsonStore store;

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private EventLog eventLog;

    @Autowired
    private ApplicationContext context;

    private LocalTelegramLoop loop;

    @BeforeEach
    void buildTheDriver() {
        loop = newLoop();
    }

    @AfterEach
    void stopTheDriver() {
        loop.stop();
    }

    /** A driver as the local profile would build it, on a cold start. */
    private LocalTelegramLoop newLoop() {
        return new LocalTelegramLoop(properties, webhook, store);
    }

    /** Telegram holds one free-text Ask from the user's chat. */
    private void telegramHoldsAnAsk() {
        SleeperStubs.healthyInSeason(sleeper);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, ANSWER);
        OutboundStubs.telegramUpdates(telegram, OutboundStubs.textMessage("lineup"));
    }

    /** The Check that turns McCaffrey Out and sends Alert 1 with its buttons. */
    private void alertOneIsOnTheLog() {
        SleeperStubs.healthyInSeason(sleeper);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "McCaffrey is Out. Bench him.");
        checkRunner.runCheck();

        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-mccaffrey-out.json", "players-v2");
        checkRunner.runCheck();

        telegram.resetRequests();
    }

    @Test
    void aQuestionSentToTheBotIsAnsweredInTheChat() {
        telegramHoldsAnAsk();

        assertThat(loop.poll()).isTrue();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.chat_id",
                        equalTo(String.valueOf(USER_CHAT_ID))))
                .withRequestBody(containing(ANSWER)));
    }

    @Test
    void theDriverAnswersOnAThreadOfItsOwn() throws InterruptedException {
        telegramHoldsAnAsk();

        loop.start();

        // Nothing here polls: the driver's own thread does, as it does
        // under the local profile once the context is up. A driver that
        // failed to stop again would break the poll counts the rest of
        // this class asserts.
        for (int wait = 0; wait < 200 && sentMessages() == 0; wait++) {
            Thread.sleep(50);
        }
        assertThat(sentMessages()).isEqualTo(1);
    }

    private int sentMessages() {
        return telegram.findAll(postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)))
                .size();
    }

    @Test
    void aButtonTapReachesTheSameSeam() {
        alertOneIsOnTheLog();
        OutboundStubs.telegramCallbackAnswered(telegram);
        OutboundStubs.telegramUpdates(telegram, OutboundStubs.callbackTap("done:1"));

        assertThat(loop.poll()).isTrue();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.ANSWER_CALLBACK_PATH))
                .withRequestBody(matchingJsonPath("$.callback_query_id", equalTo("cb-1"))));
        assertThat(eventLog.all().stream()
                .filter(event -> event.type() == EventType.USER_ACTION)
                .map(Event::key))
                .containsExactly("action:done:1");
    }

    @Test
    void anUpdateTelegramHasAlreadyGivenUsIsNotAnsweredTwice() {
        // The stub answers with the same update every time, as Telegram
        // does until a later poll confirms it.
        telegramHoldsAnAsk();

        loop.poll();
        loop.poll();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        // The second poll confirmed the first update, which is what
        // stops Telegram from offering it again.
        telegram.verify(1, getRequestedFor(urlPathEqualTo(OutboundStubs.GET_UPDATES_PATH))
                .withQueryParam("offset", equalTo("301")));
    }

    @Test
    void aRestartDoesNotReplayAnAnsweredUpdate() {
        telegramHoldsAnAsk();

        assertThat(loop.poll()).isTrue();
        // The process dies before the next poll confirms the update, so
        // Telegram offers it again to the driver that comes up next.
        assertThat(newLoop().poll()).isTrue();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void anUpdateFromAnotherChatIsDroppedAndStillConfirmed() {
        SleeperStubs.healthyInSeason(sleeper);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.telegramCallbackAnswered(telegram);
        OutboundStubs.llmPhrases(llm, ANSWER);
        OutboundStubs.telegramUpdates(telegram,
                OutboundStubs.callbackTap(9999, "done:1"),
                OutboundStubs.textMessage(9999, "lineup"));

        loop.poll();
        loop.poll();

        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH)));
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(eventLog.all().stream()
                .filter(event -> event.type() == EventType.USER_ACTION)).isEmpty();
        // The tap acts on no Alert, but it is still acknowledged, which
        // is what stops Telegram offering the same tap again.
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.ANSWER_CALLBACK_PATH)));
        // A dropped update is confirmed like any other; otherwise
        // Telegram would offer it on every poll for ever.
        telegram.verify(1, getRequestedFor(urlPathEqualTo(OutboundStubs.GET_UPDATES_PATH))
                .withQueryParam("offset", equalTo("301")));
    }

    @Test
    void aTelegramOutageDoesNotWedgeTheDriver() {
        telegram.stubFor(get(urlPathEqualTo(OutboundStubs.GET_UPDATES_PATH))
                .willReturn(aResponse().withStatus(500)));

        assertThat(loop.poll()).isFalse();

        telegramHoldsAnAsk();
        assertThat(loop.poll()).isTrue();
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing(ANSWER)));
    }

    @Test
    void theDriverIsLocalOnly() {
        // This context runs the default profile, as the deployable does.
        assertThat(context.getBeanNamesForType(LocalTelegramLoop.class)).isEmpty();
    }
}
