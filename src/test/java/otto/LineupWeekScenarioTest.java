package otto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.telegram.TelegramWebhook;
import otto.telegram.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lineup answers say which week they cover. The fixture league plays
 * week 10 with the user's roster from the Ask scenarios: every game but
 * Monday night's, DET at GB, has finished, so only Jordan Love (the
 * SUPER_FLEX starter) and Josh Jacobs (bench) can still move. Week 10
 * projects Love at 15.0 and Jacobs at 9.0. Week 11 puts KC on bye, so
 * Patrick Mahomes and Travis Kelce score nothing there, and week 12's
 * projections are not published yet.
 */
class LineupWeekScenarioTest extends WireSeamTest {

    /** Monday of week 10, a few hours before Monday Night Football. */
    private static final Instant MONDAY_OF_WEEK_10 = Instant.parse("2026-11-16T22:00:00Z");

    /** The Tuesday after, with every week 10 game final. */
    private static final Instant TUESDAY_AFTER_WEEK_10 = Instant.parse("2026-11-17T15:00:00Z");

    private static final String PROJECTIONS = "/v1/projections/nfl/regular/2026/";
    private static final String SCORES = "/scores/nfl/regular/2026/";
    private static final String TRANSACTIONS = SleeperStubs.LEAGUE_PATH + "/transactions/";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private TelegramWebhook webhook;

    private void week10League(Instant now, String week10Scores) {
        clock.set(now);
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.STATE_PATH,
                "sleeper/state-nfl-week10.json", "state-w10");
        SleeperStubs.stubJson(sleeper, PROJECTIONS + 10, "sleeper/projections-2026-10.json",
                "projections-w10");
        SleeperStubs.stubJson(sleeper, PROJECTIONS + 11, "sleeper/projections-edge.json",
                "projections-w11");
        SleeperStubs.stubJson(sleeper, PROJECTIONS + 12, "sleeper/projections-unpublished.json",
                "projections-w12");
        SleeperStubs.stubJson(sleeper, SCORES + 10, week10Scores, "scores-w10");
        SleeperStubs.stubJson(sleeper, SCORES + 11, "sleeper/scores-2026-11-kc-bye.json",
                "scores-w11");
        SleeperStubs.stubJson(sleeper, SCORES + 12, "sleeper/scores-2026-12.json", "scores-w12");
        SleeperStubs.stubJson(sleeper, TRANSACTIONS + 10, "sleeper/transactions-none.json",
                "transactions-w10");
        SleeperStubs.stubJson(sleeper, TRANSACTIONS + 9, "sleeper/transactions-none.json",
                "transactions-w9");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "A quiet week.");

        checkRunner.runCheck();

        llm.resetAll();
        telegram.resetRequests();
        OutboundStubs.telegramOk(telegram);
    }

    private void ask(String text) {
        assertThat(webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage(text)))
                .isEqualTo(WebhookResult.OK);
    }

    private void askRoutedTo(String text, String arguments) {
        llm.resetAll();
        telegram.resetRequests();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, "recommend_lineup", arguments,
                "The model's own words.");
        ask(text);
    }

    private String reply() {
        List<String> texts = telegram.findAll(
                        postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)))
                .stream()
                .map(request -> {
                    try {
                        return JSON.readTree(request.getBodyAsString()).path("text").asText();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toList();
        assertThat(texts).hasSize(1);
        return texts.getFirst();
    }

    @Test
    void mondayOfAWeekUnderWaySaysWhatIsLeftThenNextWeekPlansWeek11() {
        week10League(MONDAY_OF_WEEK_10, "sleeper/scores-2026-10-monday.json");

        askRoutedTo("Who should I start this week?", "{}");

        String monday = reply();
        assertThat(monday)
                .contains("Week 10 isn't over yet.")
                .contains("DET at GB")
                .contains("8 of your 9 starters have already played and are locked")
                .contains("Jordan Love")
                .contains("Josh Jacobs")
                .contains("I'd keep your lineup as it is")
                .contains("You can also ask me about week 11.")
                .doesNotContain("+0.0")
                .doesNotContain("The model's own words.");
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH)));

        askRoutedTo("What about for next week?", "{\"week\":\"next\"}");

        String nextWeek = reply();
        assertThat(nextWeek)
                .contains("week 11")
                .contains("Patrick Mahomes and Travis Kelce are on bye")
                .contains("139.0")
                .contains("Dallas Goedert")
                .doesNotContain("isn't over yet");
        sleeper.verify(getRequestedFor(urlEqualTo(PROJECTIONS + 11)));
        sleeper.verify(getRequestedFor(urlEqualTo(SCORES + 11)));
    }

    @Test
    void aFinishedWeekSaysSoAndPlansTheNextOne() {
        week10League(TUESDAY_AFTER_WEEK_10, "sleeper/scores-2026-10-final.json");

        askRoutedTo("Who should I start?", "{}");

        assertThat(reply())
                .contains("Week 10 is over")
                .contains("week 11")
                .contains("Patrick Mahomes and Travis Kelce are on bye")
                .contains("139.0");
    }

    @Test
    void aNamedWeekWithAByeUsesThatWeeksProjections() {
        week10League(MONDAY_OF_WEEK_10, "sleeper/scores-2026-10-monday.json");

        askRoutedTo("Who should I start in week 11?", "{\"week\":\"11\"}");

        assertThat(reply())
                .contains("week 11")
                .contains("Patrick Mahomes and Travis Kelce are on bye")
                .contains("Start <b>Dallas Goedert</b> over Travis Kelce")
                .contains("QB: Jordan Love")
                .contains("139.0")
                .contains("112.0");
    }

    @Test
    void aWeekWithoutPublishedProjectionsSaysSo() {
        week10League(MONDAY_OF_WEEK_10, "sleeper/scores-2026-10-monday.json");

        askRoutedTo("Who should I start in week 12?", "{\"week\":\"12\"}");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("hasn't published week 12 projections yet")));
    }
}
