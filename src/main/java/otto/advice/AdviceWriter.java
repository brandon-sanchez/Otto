package otto.advice;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import otto.records.ModelExchange;
import otto.storage.OttoJson;

/**
 * Gets advice the model writes from an evidence bundle, checked before
 * anyone sees it. The model forms the opinion and writes the message;
 * {@link AdviceCheck} holds it to the evidence. An answer that fails
 * gets exactly one correction attempt, which is handed the specific
 * failures; if that fails too, the advice is rejected, never patched up
 * by code.
 *
 * <p>Each call to {@link #write} makes one or two model calls with no
 * tools.
 */
@Component
public class AdviceWriter {

    private static final String SYSTEM = """
            You are Otto, a fantasy football assistant texting your one user about
            their lineup and roster, the way a knowledgeable friend would.

            The user message is an evidence bundle as JSON. State facts only from the
            evidence bundle: never from memory, never invented. Every number you write
            must appear in the evidence. Name only players listed in "players".

            Decide what the user should do. Choose one of "legalMoves", or no change
            when "noChangeAllowed" is true. Lead with the recommendation, then give
            the reason in one to three short sentences of plain text.

            When "rosterPlans" is not empty, also decide what to do with the player:
            choose exactly one plan by its "id", lead with it, and explain it in one
            natural paragraph that connects his news, the league's format and rules,
            and the roster. Name every player the plan drops or adds. Weigh the
            league's format: redraft is about this season, dynasty and keeper leagues
            also value the seasons after it. A missing projection or report is
            unknown, never zero. When "unknowns" lists evidence ids, the decision
            depends on facts no source supplied: make the advice conditional, say what
            is missing and what would change your call, and list each id in
            "conditions".

            Introduce every player by full name before using a surname alone. When
            "kickoffPhrase" is present, use it word for word. Never say a player or a
            game "locks". Never write a confidence label such as High or Medium; when
            something is uncertain, say exactly what is uncertain. Stay under
            "maxLength" characters. Never state a dollar amount unless "budget" is set.

            Answer with only this JSON object:
            {
              "message": "the text the user receives",
              "move": {"start": "<playerId>", "bench": "<playerId or null>", "slot": "<slot>"},
              "players": ["<playerId of every player the message names>"],
              "claims": [{"text": "<one factual statement>", "evidence": ["<evidence id>"]}],
              "comparisons": [{"higher": "<playerId>", "lower": "<playerId>", "basis": "projection"}],
              "plan": "<id of the chosen roster plan, or null when there are none>",
              "conditions": ["<id of each unknown the advice is conditional on>"]
            }
            Use null for "move" to recommend no lineup change.
            """;

    private static final String PROMPT_VERSION = Sha256.hex(SYSTEM).substring(0, 12);

    private static final Logger log = LoggerFactory.getLogger(AdviceWriter.class);

    private final ChatClient chat;
    private final AdviceCheck check;

    public AdviceWriter(ChatClient.Builder builder, AdviceCheck check) {
        this.chat = builder.build();
        this.check = check;
    }

    /** Identifies the instructions the model wrote under, for the Decision Record. */
    public static String promptVersion() {
        return PROMPT_VERSION;
    }

    /** Asks for advice, checks it, and corrects it once when it fails. */
    public AdviceResult write(EvidenceBundle bundle) {
        List<Message> conversation = new ArrayList<>();
        conversation.add(new UserMessage(json(bundle)));
        List<ModelExchange> exchanges = new ArrayList<>();
        List<String> failures = List.of();
        for (int attempt = 1; attempt <= 2; attempt++) {
            JsonNode request = request(conversation);
            String reply;
            try {
                reply = chat.prompt().system(SYSTEM).messages(conversation).call().content();
            } catch (RuntimeException e) {
                log.warn("Advice model call failed on attempt {}", attempt, e);
                String reason = "The model could not be reached: " + e.getClass().getSimpleName();
                exchanges.add(new ModelExchange(attempt, request, NullNode.getInstance(),
                        List.of(reason)));
                return new AdviceResult.Unavailable(reason, exchanges);
            }
            if (reply == null || reply.isBlank()) {
                String reason = "The model answered with nothing.";
                exchanges.add(new ModelExchange(attempt, request, TextNode.valueOf(""),
                        List.of(reason)));
                return new AdviceResult.Unavailable(reason, exchanges);
            }
            AdviceCheck.Verdict verdict = check.check(bundle, reply);
            exchanges.add(new ModelExchange(attempt, request, response(reply),
                    verdict.failures()));
            if (verdict.passed()) {
                return new AdviceResult.Approved(verdict.answer().orElseThrow(), exchanges);
            }
            failures = verdict.failures();
            log.info("Advice attempt {} failed its checks: {}", attempt, failures);
            conversation.add(new AssistantMessage(reply));
            conversation.add(new UserMessage(correction(failures)));
        }
        return new AdviceResult.Rejected(failures, exchanges);
    }

    private static String correction(List<String> failures) {
        StringBuilder text = new StringBuilder("Your answer failed these checks:");
        failures.forEach(failure -> text.append("\n- ").append(failure));
        return text.append("\nReturn the corrected JSON answer.").toString();
    }

    private static JsonNode request(List<Message> conversation) {
        ObjectNode request = OttoJson.MAPPER.createObjectNode();
        request.put("promptVersion", PROMPT_VERSION);
        ArrayNode messages = request.putArray("messages");
        messages.addObject().put("role", "system").put("content", SYSTEM);
        conversation.forEach(message -> messages.addObject()
                .put("role", message instanceof AssistantMessage ? "assistant" : "user")
                .put("content", message.getText()));
        return request;
    }

    private static JsonNode response(String reply) {
        try {
            return OttoJson.MAPPER.readTree(reply);
        } catch (IOException e) {
            return TextNode.valueOf(reply);
        }
    }

    private static String json(EvidenceBundle bundle) {
        try {
            return OttoJson.MAPPER.writeValueAsString(bundle);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot write an evidence bundle", e);
        }
    }
}
