package otto;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.telegram.TelegramWebhook;
import otto.telegram.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Ask loop: free text from the user's chat routes through the Lane A
 * agent loop over the deterministic tools, and the model narrates what
 * the tools computed. Java owns every number in these scenarios; the
 * canned tool-call sequences make the routing deterministic.
 *
 * The fixture roster starts Mahomes 22.0, McCaffrey 18.0, Bijan 16.0,
 * Jefferson 19.0, Lamb 17.0, Kelce 11.0, Wilson 14.0, Cook 13.0 and
 * Love 15.0 for 145.0 projected points. Jacobs (16.5) sits on the bench
 * over Cook (13.0), so the optimal legal lineup projects 148.5.
 */
class AskScenarioTest extends WireSeamTest {

    private static final String LINEUP_PHRASE = "Start Jacobs over Cook: +3.5 points.";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private TelegramWebhook webhook;

    /**
     * Ask tools read the latest Snapshot, so a Check runs first. The
     * edge Alert it sends is not what these scenarios observe: the LLM
     * and Telegram journals reset before the Ask.
     */
    private void snapshotWithABenchEdge() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH,
                "sleeper/projections-edge.json", "projections-v1");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "An alert about the bench edge.");

        checkRunner.runCheck();

        llm.resetAll();
        telegram.resetRequests();
    }

    private void ask(String text) {
        assertThat(webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage(text)))
                .isEqualTo(WebhookResult.OK);
    }

    @Test
    void aLineupQuestionIsAnsweredFromTheRecommendLineupTool() {
        snapshotWithABenchEdge();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, "recommend_lineup", "{}", LINEUP_PHRASE);

        ask("lineup");

        // The user gets the model's narration, not raw tool JSON.
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing(LINEUP_PHRASE)));

        // Two model calls: route to the tool, then narrate its result.
        // The numbers reach the model from Java, never from the model.
        llm.verify(2, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH)));
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("145.0"))
                .withRequestBody(containing("148.5"))
                .withRequestBody(containing("+3.5"))
                .withRequestBody(containing("Josh Jacobs"))
                .withRequestBody(containing("James Cook"))
                .withRequestBody(notContaining("99.9")));
    }

    @Test
    void theModelIsOfferedEveryLaneATool() {
        snapshotWithABenchEdge();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Anything you like.");

        ask("what should I do this week?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("get_roster_status"))
                .withRequestBody(containing("get_league_settings"))
                .withRequestBody(containing("recommend_lineup"))
                .withRequestBody(containing("whatif_lineup"))
                .withRequestBody(containing("compare_players"))
                .withRequestBody(containing("get_player_news")));
    }

    @Test
    void aWhatIfQuestionProjectsTheSwapBeforeTheUserActs() {
        snapshotWithABenchEdge();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, "whatif_lineup",
                "{\"start\":\"Josh Jacobs\",\"sit\":\"James Cook\"}",
                "That swap gains you 3.5 points.");

        ask("what if I start Jacobs over Cook?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("145.0"))
                .withRequestBody(containing("148.5"))
                .withRequestBody(containing("+3.5")));
    }

    @Test
    void aWhatIfThatCostsPointsSaysSo() {
        snapshotWithABenchEdge();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, "whatif_lineup",
                "{\"start\":\"Dallas Goedert\"}",
                "Goedert would cost you half a point.");

        ask("what if I start Goedert?");

        // Goedert (10.5) can only replace Kelce (11.0) in the TE slot,
        // the weakest starter whose slot he fits: 148.5 stays out of
        // reach and the lineup loses 0.5.
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("144.5"))
                .withRequestBody(containing("-0.5"))
                .withRequestBody(containing("Travis Kelce")));
    }

    @Test
    void aWhatIfPicksOnlyASlotTheUserCanStillFree() {
        snapshotWithABenchEdge();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, "whatif_lineup",
                "{\"start\":\"Josh Jacobs\"}", "Jacobs for Love gains you 1.5.");

        // Sunday afternoon: the 17:00 UTC games have kicked off, so
        // every RB-capable slot has locked except SUPER_FLEX, where
        // Love (GB, Monday) still sits.
        clock.set(Instant.parse("2026-09-20T18:00:00Z"));

        ask("what if I start Jacobs?");

        // Cook projects lowest of the slots an RB fits, but his game is
        // underway and Sleeper will not take him out. Love is the
        // weakest starter the user can still move: 145.0 - 15.0 + 16.5.
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Jordan Love"))
                .withRequestBody(containing("146.5"))
                .withRequestBody(containing("+1.5"))
                .withRequestBody(notContaining("James Cook")));
    }

    @Test
    void rosterStatusReadsTheStoredSnapshot() {
        snapshotWithABenchEdge();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_roster_status", "{}",
                "Your starters are healthy.");

        ask("how does my roster look?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Patrick Mahomes"))
                .withRequestBody(containing("SUPER_FLEX"))
                .withRequestBody(containing("Josh Jacobs")));
    }

    @Test
    void leagueSettingsComeFromTheLeagueDocument() {
        snapshotWithABenchEdge();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_league_settings", "{}",
                "Superflex, full PPR with a TE premium.");

        ask("what are my league settings?");

        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Raising Canes Demon"))
                .withRequestBody(containing("SUPER_FLEX"))
                .withRequestBody(containing("bonus_rec_te")));
    }

    @Test
    void aToolWithoutASnapshotSaysWhatItCannotSee() {
        SleeperStubs.healthyInSeason(sleeper);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmCallsToolThenPhrases(llm, "get_roster_status", "{}",
                "I have not built a snapshot of your roster yet.");

        ask("how does my roster look?");

        // No Check has run, so the tool reports the blind spot instead
        // of guessing; silence never masquerades as certainty.
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("no Snapshot stored yet")));
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void theConversationWindowKeepsTheLastTwentyMessages() {
        snapshotWithABenchEdge();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Noted.");

        for (int turn = 1; turn <= 12; turn++) {
            ask("ask-%02d".formatted(turn));
        }

        // Each Ask adds two messages. By the twelfth, the window has
        // rolled past the first exchange but still carries the second.
        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("ask-01"))
                .withRequestBody(containing("ask-12")));
        llm.verify(1, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("ask-02"))
                .withRequestBody(containing("ask-12")));
    }

    @Test
    void theConversationWindowDropsMessagesOlderThanADay() {
        snapshotWithABenchEdge();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Noted.");

        ask("ask-yesterday");
        clock.advance(Duration.ofHours(25));
        ask("ask-today");

        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("ask-yesterday"))
                .withRequestBody(containing("ask-today")));
    }

    @Test
    void repliesAreShortByDefaultAndDeeperWhenTheUserAsksWhy() {
        snapshotWithABenchEdge();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Noted.");

        ask("lineup");
        ask("why?");
        ask("tell me more");
        ask("I want more points from my flex this year");

        // Short by default; "why" and a short "more" open the answer up.
        // A long question that merely says "more" is a fresh question.
        llm.verify(2, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("2 to 5 lines"))
                .withRequestBody(notContaining("Give the full reasoning")));
        llm.verify(2, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("Give the full reasoning")));
    }

    @Test
    void anOutageReplyNeverEntersTheConversationWindow() {
        snapshotWithABenchEdge();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmRefuses(llm);

        ask("ask-during-outage");

        llm.resetAll();
        OutboundStubs.llmPhrases(llm, "Noted.");
        ask("ask-after-outage");

        // A spell of outages must not evict the real conversation, and
        // the model must never read its own outage text as a past answer.
        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("ask-during-outage")));
        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing("cannot reach")));
    }

    @Test
    void aModelOutageStillGetsAnHonestReply() {
        snapshotWithABenchEdge();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmRefuses(llm);

        ask("lineup");

        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH))
                .withRequestBody(containing("cannot reach")));
    }

    @Test
    void aMessageFromAnotherChatIsIgnored() {
        snapshotWithABenchEdge();
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "Noted.");

        assertThat(webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage(9999, "lineup")))
                .isEqualTo(WebhookResult.OK);

        llm.verify(0, postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH)));
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }
}
