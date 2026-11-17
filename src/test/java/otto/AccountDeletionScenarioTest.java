package otto;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.harness.OutboundStubs;
import otto.harness.SampleRecords;
import otto.harness.WireSeamTest;
import otto.records.DecisionRecords;
import otto.storage.OttoJson;
import otto.telegram.TelegramWebhook;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The owner deletes his saved advice history from the chat. The command
 * only asks; nothing is deleted until he taps Confirm on that same
 * question, in the same chat, before it expires.
 */
class AccountDeletionScenarioTest extends WireSeamTest {

    private static final String OWNER = "777001";
    private static final String SOMEONE_ELSE = "777002";
    private static final String LEAGUE = "league-1";
    private static final List<String> RUNS = List.of(
            "20261010T153000Z-3f9a1c2e", "20261017T153000Z-4b5c6d7e");

    @Autowired
    private TelegramWebhook webhook;

    @Autowired
    private DecisionRecords records;

    @Autowired
    private OttoProperties properties;

    @BeforeEach
    void ownerHasAdviceHistory() {
        OutboundStubs.telegramOk(telegram);
        OutboundStubs.telegramCallbackAnswered(telegram);
        RUNS.forEach(run -> SampleRecords.storeRun(records, OWNER, LEAGUE, run));
        SampleRecords.storeRun(records, OWNER, "league-2", "20261011T090000Z-0b1c2d3e");
        SampleRecords.storeRun(records, SOMEONE_ELSE, LEAGUE, "20261010T153000Z-77aa88bb");
    }

    @Test
    void theCommandSaysWhatGoesAndAsksBeforeDeletingAnything() {
        webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage("/deleteaccount"));

        JsonNode prompt = lastMessage();
        assertThat(prompt.path("text").asText())
                .contains("advice history", "outcomes", "article text", "can't be undone");
        JsonNode buttons = prompt.path("reply_markup").path("inline_keyboard").path(0);
        assertThat(buttons.path(0).path("text").asText()).isEqualTo("Confirm");
        assertThat(buttons.path(1).path("text").asText()).isEqualTo("Cancel");
        assertThat(buttons.path(0).path("callback_data").asText().length())
                .as("Telegram's callback-data limit").isLessThanOrEqualTo(64);
        assertOwnerHistoryKept();
        llm.verify(0, postRequestedFor(urlMatching(OutboundStubs.CHAT_COMPLETIONS_PATH)));
    }

    @Test
    void confirmingDeletesEveryRecordOutcomeAndArticleTextOfTheOwnerOnly() {
        webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage("/deleteaccount"));

        webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap(button("Confirm")));

        assertThat(storedFilesOf(OWNER)).as("no record, outcome or article text").isEmpty();
        RUNS.forEach(run -> {
            assertThat(records.read(OWNER, LEAGUE, run)).isEmpty();
            assertThat(records.readOutcome(OWNER, LEAGUE, run, Map.class)).isEmpty();
        });
        assertThat(records.read(SOMEONE_ELSE, LEAGUE, "20261010T153000Z-77aa88bb"))
                .isPresent();
        assertThat(lastMessage().path("text").asText())
                .contains("deleted", "advice history");
        telegram.verify(postRequestedFor(urlEqualTo(OutboundStubs.ANSWER_CALLBACK_PATH))
                .withRequestBody(matchingJsonPath("$.callback_query_id", equalTo("cb-1"))));
    }

    @Test
    void cancellingDeletesNothingAndTheQuestionCannotBeConfirmedAfterwards() {
        webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage("/deleteaccount"));
        String confirm = button("Confirm");

        webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap(button("Cancel")));
        webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap(confirm));

        assertOwnerHistoryKept();
        telegram.verify(postRequestedFor(urlEqualTo(OutboundStubs.ANSWER_CALLBACK_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(
                        "Cancelled. Your advice history is still saved."))));
    }

    @Test
    void aConfirmAfterTheQuestionExpiresDeletesNothing() {
        webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage("/deleteaccount"));
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap(button("Confirm")));

        assertOwnerHistoryKept();
        telegram.verify(postRequestedFor(urlEqualTo(OutboundStubs.ANSWER_CALLBACK_PATH))
                .withRequestBody(matchingJsonPath("$.text",
                        equalTo("That request expired, so nothing was deleted."
                                + " Send /deleteaccount to start again."))));
    }

    @Test
    void theCommandPickedFromTheMenuInAGroupAsksToo() {
        webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage("/deleteaccount@OttoBot"));

        assertThat(button("Confirm")).startsWith("account:confirm:");
    }

    @Test
    void aSecondConfirmAfterTheDeletionIsToldTheRequestIsClosed() {
        webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage("/deleteaccount"));
        String confirm = button("Confirm");
        webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap(confirm));
        telegram.resetRequests();

        webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap(confirm));

        telegram.verify(postRequestedFor(urlEqualTo(OutboundStubs.ANSWER_CALLBACK_PATH))
                .withRequestBody(matchingJsonPath("$.text", equalTo(
                        "That request is no longer open, so nothing was deleted."
                                + " Send /deleteaccount to start again."))));
        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    @Test
    void aForgedConfirmDeletesNothing() {
        webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage("/deleteaccount"));

        webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap(
                "account:confirm:00000000000000000000000000000000"));
        webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap("account:confirm:"));

        assertOwnerHistoryKept();
    }

    @Test
    void aConfirmWithNoQuestionAskedDeletesNothing() {
        webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap(
                "account:confirm:00000000000000000000000000000000"));

        assertOwnerHistoryKept();
    }

    @Test
    void aConfirmFromAnotherChatDeletesNothing() {
        webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage("/deleteaccount"));

        webhook.handle(WEBHOOK_SECRET, OutboundStubs.callbackTap(9999, button("Confirm")));

        assertOwnerHistoryKept();
    }

    @Test
    void theCommandFromAnotherChatAsksNothing() {
        webhook.handle(WEBHOOK_SECRET, OutboundStubs.textMessage(9999, "/deleteaccount"));

        telegram.verify(0, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    private void assertOwnerHistoryKept() {
        RUNS.forEach(run -> {
            assertThat(records.read(OWNER, LEAGUE, run)).isPresent();
            assertThat(records.readOutcome(OWNER, LEAGUE, run, Map.class)).isPresent();
        });
        assertThat(storedFilesOf(OWNER))
                .as("a record, its article text and its outcome for every run")
                .hasSize((RUNS.size() + 1) * 3);
    }

    private String button(String label) {
        for (JsonNode button : lastMessage().path("reply_markup").path("inline_keyboard")
                .path(0)) {
            if (label.equals(button.path("text").asText())) {
                return button.path("callback_data").asText();
            }
        }
        throw new AssertionError("no " + label + " button on the last message");
    }

    private JsonNode lastMessage() {
        List<LoggedRequest> sent = telegram.findAll(
                postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
        assertThat(sent).as("a message to the chat").isNotEmpty();
        try {
            return OttoJson.MAPPER.readTree(sent.getLast().getBodyAsString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<Path> storedFilesOf(String userId) {
        Path dir = Path.of(properties.storageDir(), "decision-records");
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.toString().contains("user=" + userId + "/"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
