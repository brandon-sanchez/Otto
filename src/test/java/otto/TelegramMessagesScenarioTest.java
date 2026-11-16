package otto;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.harness.OutboundStubs;
import otto.harness.SleeperStubs;
import otto.harness.WireSeamTest;
import otto.storage.OttoJson;
import otto.telegram.TelegramClient;
import otto.telegram.TelegramWebhook;
import otto.telegram.WebhookResult;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every message Otto sends goes out within Telegram's 4,096-character
 * limit, split at paragraphs, with an Alert's buttons on its last part,
 * and with no stray Markdown, since Otto sends plain text.
 */
class TelegramMessagesScenarioTest extends WireSeamTest {

    private static final int TELEGRAM_LIMIT = 4096;

    private static final String ROSTER = """
            ----- STARTERS -----
            QB: Patrick Mahomes
            RB: Bijan Robinson
            WR: Justin Jefferson

            ----- BENCH -----
            RB: Josh Jacobs
            TE: Dallas Goedert""";

    @Autowired
    private TelegramClient client;

    @Autowired
    private TelegramWebhook webhook;

    @BeforeEach
    void telegramAnswers() {
        OutboundStubs.telegramOk(telegram);
    }

    @Test
    void aLongChatAnswerGoesOutAsSeveralMessagesSplitBetweenParagraphs() {
        SleeperStubs.healthyInSeason(sleeper);
        String answer = String.join("\n\n", paragraph("first", 2000), paragraph("second", 2000),
                ROSTER, paragraph("third", 1800));
        OutboundStubs.llmPhrases(llm, answer);

        assertThat(webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage("how's my team?")))
                .isEqualTo(WebhookResult.OK);

        List<String> sent = sentTexts();
        assertThat(sent).hasSizeGreaterThan(1)
                .allSatisfy(part -> assertThat(part).hasSizeLessThanOrEqualTo(TELEGRAM_LIMIT));
        assertThat(String.join("\n\n", sent)).isEqualTo(answer);
        assertThat(sent).anySatisfy(part -> assertThat(part).contains(ROSTER));
    }

    @Test
    void aLongAlertKeepsItsButtonsOnItsLastPart() {
        String alert = String.join("\n\n", paragraph("first", 2500), paragraph("second", 2500));

        assertThat(client.sendAlert(alert, 7, true)).isTrue();

        List<JsonNode> sent = sentBodies();
        assertThat(sent).hasSize(2);
        assertThat(sent.getFirst().has("reply_markup")).isFalse();
        assertThat(sent.getLast().at("/reply_markup/inline_keyboard/0/0/callback_data").asText())
                .isEqualTo("done:7");
        assertThat(sent.getLast().at("/reply_markup/inline_keyboard/1/0/callback_data").asText())
                .isEqualTo("all:7");
        assertThat(sent.getFirst().path("text").asText() + "\n\n"
                + sent.getLast().path("text").asText()).isEqualTo(alert);
    }

    @Test
    void aParagraphLongerThanTheLimitIsSplitBetweenLines() {
        String lines = String.join("\n", java.util.Collections.nCopies(120,
                "WR: a receiver on the full board, listed on a line of his own"));

        assertThat(client.sendMessage(lines)).isTrue();

        List<String> sent = sentTexts();
        assertThat(sent).hasSize(2)
                .allSatisfy(part -> assertThat(part).hasSizeLessThanOrEqualTo(TELEGRAM_LIMIT));
        assertThat(String.join("\n", sent)).isEqualTo(lines);
    }

    @Test
    void strayMarkdownAndExtraBlankLinesAreTidiedAway() {
        assertThat(client.sendMessage("## Your lineup  \n**I'd start Josh Jacobs**, because "
                + "he's healthy.\n\n\n\nHe faces the __worst__ run defense.  \n")).isTrue();

        assertThat(sentTexts()).containsExactly("Your lineup\nI'd start Josh Jacobs, because "
                + "he's healthy.\n\nHe faces the worst run defense.");
    }

    private static String paragraph(String name, int length) {
        String sentence = "This is the %s paragraph, which says one more thing about your team. "
                .formatted(name);
        return sentence.repeat(length / sentence.length() + 1).substring(0, length).strip()
                + ".";
    }

    private static List<String> sentTexts() {
        return sentBodies().stream().map(body -> body.path("text").asText()).toList();
    }

    private static List<JsonNode> sentBodies() {
        return telegram.findAll(postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)))
                .stream()
                .map(request -> {
                    try {
                        return OttoJson.MAPPER.readTree(request.getBodyAsString());
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toList();
    }
}
