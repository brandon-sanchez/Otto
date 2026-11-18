package otto;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.check.CheckRunner;
import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.webhook.TelegramWebhook;
import otto.webhook.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Otto's casual first-person voice always comes with its reasons: every
 * prompt that has the model write for the user tells it to give each
 * recommendation a short reason.
 */
class VoiceScenarioTest extends WireSeamTest {

    private static final String REASON_RULE = "Give every recommendation a reason";

    private static final String FIRST_PERSON = "in the first person";

    @Autowired
    private CheckRunner checkRunner;

    @Autowired
    private TelegramWebhook webhook;

    @Test
    void anAlertIsPhrasedWithAReasonForWhatItRecommends() {
        SleeperStubs.healthyInSeason(sleeper);
        SleeperStubs.stubJson(sleeper, SleeperStubs.PROJECTIONS_PATH,
                "sleeper/projections-edge.json", "projections-v1");
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "I'd start Josh Jacobs, because he projects higher.");

        checkRunner.runCheck();

        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(REASON_RULE))
                .withRequestBody(containing(FIRST_PERSON)));
    }

    @Test
    void aChatAnswerIsWrittenWithAReasonForWhatItRecommends() {
        SleeperStubs.healthyInSeason(sleeper);
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.llmPhrases(llm, "I'd hold, because nobody on the wire beats your bench.");

        assertThat(webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage("any moves?")))
                .isEqualTo(WebhookResult.OK);

        llm.verify(postRequestedFor(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .withRequestBody(containing(REASON_RULE))
                .withRequestBody(containing(FIRST_PERSON)));
    }
}
