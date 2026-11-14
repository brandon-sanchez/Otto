package otto;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.harness.AdviceStubs;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

/**
 * An Alert Telegram did not accept is checked again on the next Check
 * before it goes out: sent unchanged when nothing it rests on moved,
 * rewritten when the news or the player's status did, and cancelled
 * when its advice no longer holds.
 *
 * <p>The fixture roster starts Christian McCaffrey (4034), whose
 * decline to Out is the delayed Alert.
 */
class DelayedAlertScenarioTest extends WireSeamTest {

    private static final String MCCAFFREY = "4034";

    private static final String FIRST_PHRASE = AdviceStubs.MCCAFFREY_OUT_HOLD_MESSAGE;

    private static final String UPDATED_PHRASE = "Move Christian McCaffrey to IR and start "
            + "Josh Jacobs at RB. The 49ers placed Christian McCaffrey on injured reserve.";

    private static final String RECOVERY_PHRASE =
            "Christian McCaffrey is back to Active, so keep him where he is.";

    @Autowired
    private CheckRunner checkRunner;

    private void declineWhileTelegramIsDown() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.noNewsByDefault(sleeper);
        OutboundStubs.llmPhrases(llm, "Baseline.");
        AdviceStubs.llmDecidesOn(llm, "Out", AdviceStubs.MCCAFFREY_OUT_HOLD);
        telegram.stubFor(WireMock.post(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .willReturn(aResponse().withStatus(500)));
        checkRunner.runCheck();

        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.noNewsByDefault(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-mccaffrey-out.json", "players-v2");
        checkRunner.runCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(FIRST_PHRASE))));
        telegram.resetAll();
        OutboundStubs.telegramOk(telegram);
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.noNewsByDefault(sleeper);
    }

    private static String newsFeed(String title, String description, Instant published) {
        return """
                [{
                  "metadata": {"title": "%s", "description": "%s", "analysis": null,
                               "url": "https://www.rotowire.com/football/news/cmc-901"},
                  "source": "rotowire",
                  "sport": "nfl",
                  "source_key": "901",
                  "published": %d
                }]""".formatted(title, description, published.toEpochMilli());
    }

    @Test
    void aDelayedAlertWhoseFactsHaveNotMovedGoesOutUnchangedWithoutAskingTheModelAgain() {
        declineWhileTelegramIsDown();
        llm.resetRequests();
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.PLAYERS_PATH, "players-v2");

        checkRunner.runCheck();

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(FIRST_PHRASE)))
                .withRequestBody(matchingJsonPath(
                        "$.reply_markup.inline_keyboard[0][0].callback_data",
                        equalTo("done:1"))));
        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH)));
        sleeper.verify(1, WireMock.getRequestedFor(
                urlPathEqualTo("/players/nfl/" + MCCAFFREY + "/news")));
    }

    @Test
    void aDelayedAlertIsRewrittenAndCheckedAgainWhenNewsArrivedWhileItWaited() {
        declineWhileTelegramIsDown();
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.PLAYERS_PATH, "players-v2");
        sleeper.stubFor(get(urlPathEqualTo("/players/nfl/" + MCCAFFREY + "/news"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(newsFeed("Christian McCaffrey - Placed on injured reserve",
                                "The 49ers placed Christian McCaffrey on injured reserve.",
                                clock.instant().minus(Duration.ofMinutes(1))))));
        llm.resetAll();
        OutboundStubs.llmAdvisesOn(llm, "Placed on injured reserve", AdviceStubs.decision(
                UPDATED_PHRASE, "ir", AdviceStubs.JACOBS_FOR_MCCAFFREY,
                List.of("4034", "5850"), List.of()));

        checkRunner.runCheck();

        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Placed on injured reserve")));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(UPDATED_PHRASE)))
                .withRequestBody(matchingJsonPath(
                        "$.reply_markup.inline_keyboard[0][0].callback_data",
                        equalTo("done:1"))));
    }

    @Test
    void aDelayedAlertIsCancelledWhenThePlayerRecoveredWhileItWaited() {
        declineWhileTelegramIsDown();
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl.json", "players-v3");
        llm.resetAll();
        AdviceStubs.llmDecidesOn(llm, "Active", AdviceStubs.decision(RECOVERY_PHRASE, "hold",
                "null", List.of("4034"), List.of()));

        checkRunner.runCheck();
        clock.advance(Duration.ofSeconds(61));
        sleeper.resetAll();
        SleeperStubs.allNotModified(sleeper);
        SleeperStubs.noNewsByDefault(sleeper);
        SleeperStubs.stubNotModified(sleeper, SleeperStubs.PLAYERS_PATH, "players-v3");
        checkRunner.runCheck();

        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(FIRST_PHRASE))));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(RECOVERY_PHRASE))));
    }
}
