package otto;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.alerts.MuteStore;
import otto.check.CheckRunner;
import otto.events.EventLog;
import otto.events.EventType;
import otto.followed.FollowedLeagues;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.TwoLeagues;
import otto.harness.WireSeamTest;
import otto.market.MarketValueRefresh;
import otto.sleeper.LeagueScope;
import otto.storage.OttoJson;
import otto.telegram.TelegramWebhook;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * One Check covers both of the owner's leagues. McCaffrey is on his
 * roster in each, so his turn to Out is an Alert in each, and each
 * Alert names its league and acts on its own league when tapped.
 */
class MultiLeagueCheckScenarioTest extends WireSeamTest {

    private static final String PHRASE = "McCaffrey is Out. Bench him.";
    private static final String NEWS = "Christian McCaffrey went from Active to Out.";
    private static final String MCCAFFREY = "4034";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private TelegramWebhook webhook;

    @Autowired
    private EventLog eventLog;

    @Autowired
    private MuteStore mutes;

    @Autowired
    private FollowedLeagues leagues;

    @Autowired
    private MarketValueRefresh marketValues;

    private void baselineCheck() {
        TwoLeagues.healthyInSeason(sleeper);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, PHRASE);
        checkRunner.runCheck();
    }

    private void mccaffreyTurnsOut() {
        clock.advance(Duration.ofSeconds(61));
        SleeperStubs.stubJson(sleeper, SleeperStubs.PLAYERS_PATH,
                "sleeper/players-nfl-mccaffrey-out.json", "players-v2");
        checkRunner.runCheck();
    }

    private static List<JsonNode> alertsSent() {
        return telegram.findAll(postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)))
                .stream()
                .map(LoggedRequest::getBodyAsString)
                .map(MultiLeagueCheckScenarioTest::json)
                .filter(body -> body.has("reply_markup"))
                .toList();
    }

    private static JsonNode alertIn(String leagueName) {
        return alertsSent().stream()
                .filter(body -> body.path("text").asText().startsWith("League: " + leagueName))
                .findFirst()
                .orElseThrow();
    }

    private static String muteButton(JsonNode alert) {
        return alert.path("reply_markup").path("inline_keyboard").get(0).get(2)
                .path("callback_data").asText();
    }

    @Test
    void eachLeagueGetsItsOwnAlertNamingItsLeague() {
        baselineCheck();
        mccaffreyTurnsOut();

        assertThat(alertsSent()).hasSize(2);
        assertThat(alertIn(TwoLeagues.CONFIG_LEAGUE_NAME).path("text").asText())
                .contains(NEWS);
        assertThat(alertIn(TwoLeagues.DYNASTY_LEAGUE_NAME).path("text").asText())
                .contains(NEWS);
    }

    @Test
    void aButtonActsOnTheLeagueItsAlertCameFrom() {
        baselineCheck();
        mccaffreyTurnsOut();
        OutboundStubs.telegramCallbackAnswered(telegram);

        webhook.handle(WEBHOOK_SECRET,
                OutboundStubs.callbackTap(muteButton(alertIn(TwoLeagues.DYNASTY_LEAGUE_NAME))));

        assertThat(LeagueScope.call(TwoLeagues.DYNASTY_LEAGUE,
                () -> mutes.muted(MuteStore.playerTarget(MCCAFFREY)))).isTrue();
        assertThat(mutes.muted(MuteStore.playerTarget(MCCAFFREY))).isFalse();
        assertThat(LeagueScope.call(TwoLeagues.DYNASTY_LEAGUE, () -> eventLog.all()))
                .anyMatch(event -> event.type() == EventType.USER_ACTION);
        assertThat(eventLog.all()).noneMatch(event -> event.type() == EventType.USER_ACTION);
    }

    @Test
    void aLeagueLeftOutIsNotCheckedAndTheLastLeagueIsNoLongerNamed() {
        baselineCheck();
        leagues.leaveOut(TwoLeagues.DYNASTY_LEAGUE);
        telegram.resetRequests();

        mccaffreyTurnsOut();

        assertThat(alertsSent()).singleElement().satisfies(alert ->
                assertThat(alert.path("text").asText()).startsWith(NEWS));
    }

    @Test
    void theMarketValueJobReadsTheListEachLeaguesFormatNeeds() {
        baselineCheck();

        marketValues.refresh();

        fantasyCalc.verify(getRequestedFor(urlPathEqualTo("/values/current"))
                .withQueryParam("isDynasty", equalTo("false")));
        fantasyCalc.verify(getRequestedFor(urlPathEqualTo("/values/current"))
                .withQueryParam("isDynasty", equalTo("true")));
    }

    private static JsonNode json(String body) {
        try {
            return OttoJson.MAPPER.readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
