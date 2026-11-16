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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import otto.model.ChatProvider;
import otto.model.ModelChain;
import otto.records.ModelExchange;
import otto.storage.OttoJson;

/**
 * Gets advice the model writes from an evidence bundle, checked before
 * anyone sees it. The model forms the opinion and writes the message;
 * its {@link AdviceForm}, built on {@link AdviceCheck}, holds it to the
 * evidence. An answer that fails gets exactly one correction attempt,
 * which is handed the specific failures; if that fails too, the advice
 * is rejected, never patched up by code.
 *
 * <p>Each call to {@code write} makes one or two model calls with no
 * tools to each provider the {@link ModelChain} tries, and tries the
 * next provider only when the one before it failed.
 */
@Component
public class AdviceWriter {

    private static final String GROUNDING = """
            You are Otto, a fantasy football assistant texting your one user about
            their lineup and roster, the way a knowledgeable friend would.

            The user message is an evidence bundle as JSON. State facts only from the
            evidence bundle: never from memory, never invented. Every number you write
            must appear in the evidence. Name only players listed in "players".

            Decide what the user should do. Choose one of "legalMoves", or no change
            when "noChangeAllowed" is true. Lead with the recommendation, then give
            the reason in one to three short sentences of plain text.

            """;

    private static final String PLAYER_DECISION = """
            Also decide what to do with the player the decision is about: choose
            exactly one of "rosterPlans" by its "id", lead with it, and explain it in
            one natural paragraph that connects his news, the league's format and
            rules, and the roster. Name every player the plan drops or adds. Weigh the
            league's format: redraft is about this season, dynasty and keeper leagues
            also value the seasons after it. A missing projection or report is
            unknown, never zero. When "unknowns" lists evidence ids, the decision
            depends on facts no source supplied: make the advice conditional, say what
            is missing and what would change your call, and list each id in
            "conditions".

            """;

    private static final String VOICE = """
            Introduce every player by full name before using a surname alone. When
            "kickoffPhrase" is present, use it word for word. Never say a player or a
            game "locks". Never write a confidence label such as High or Medium; when
            something is uncertain, say exactly what is uncertain. Stay under
            "maxLength" characters. Never state a dollar amount unless "budget" is set.

            """;

    private static final String ANSWER_FIELDS = String.join(",\n",
            "  \"message\": \"the text the user receives\"",
            "  \"move\": {\"start\": \"<playerId>\", \"bench\": \"<playerId or null>\", "
                    + "\"slot\": \"<slot>\"}",
            "  \"players\": [\"<playerId of every player the message names>\"]",
            "  \"claims\": [{\"text\": \"<one factual statement>\", "
                    + "\"evidence\": [\"<evidence id>\"]}]",
            "  \"comparisons\": [{\"higher\": \"<playerId>\", \"lower\": \"<playerId>\", "
                    + "\"basis\": \"projection\"}]");

    private static final String LINEUP_SHAPE = "Answer with only this JSON object:\n{\n"
            + ANSWER_FIELDS + "\n}\nUse null for \"move\" to recommend no change.\n";

    private static final String DECISION_SHAPE = "Answer with only this JSON object:\n{\n"
            + ANSWER_FIELDS + ",\n"
            + "  \"plan\": \"<id of the chosen roster plan>\",\n"
            + "  \"conditions\": [\"<id of each unknown the advice is conditional on>\"]\n"
            + "}\nUse null for \"move\" to recommend no lineup change.\n";

    /** One answer, then one correction carrying its failures. */
    private static final int ATTEMPTS = 2;

    private static final Logger log = LoggerFactory.getLogger(AdviceWriter.class);

    private final ModelChain chain;
    private final AdviceForm<AdviceAnswer> lineup;
    private final AdviceForm<AdviceAnswer> playerDecision;

    /** Asks models through {@code chain} and checks answers with {@code check}. */
    public AdviceWriter(ModelChain chain, AdviceCheck check) {
        this.chain = chain;
        this.lineup = answerForm(GROUNDING + VOICE + LINEUP_SHAPE, check);
        this.playerDecision = answerForm(GROUNDING + PLAYER_DECISION + VOICE + DECISION_SHAPE,
                check);
    }

    /**
     * The form for bench-edge, lineup-legality and pregame advice: one
     * lineup move, or none.
     */
    public AdviceForm<AdviceAnswer> lineup() {
        return lineup;
    }

    /**
     * The form for a status change on one of the user's players: a
     * lineup move plus one of the bundle's roster plans, conditional on
     * every unknown the bundle lists.
     */
    public AdviceForm<AdviceAnswer> playerDecision() {
        return playerDecision;
    }

    /**
     * Asks for advice of one form, checks it, and corrects it once when
     * it fails. A provider that cannot be reached, or whose reply cannot
     * be read, is skipped, and the next provider in the chain gets the
     * same bundle and instructions. The correction stays with the
     * provider that wrote the failed answer. The last provider the chain
     * can ask gets a correction for an unreadable reply too, since no
     * one is left to skip to.
     */
    public <T> AdviceResult<T> write(EvidenceBundle bundle, AdviceForm<T> form) {
        List<ModelExchange> exchanges = new ArrayList<>();
        ModelChain.ProviderCall<AdviceResult<T>> turns = new ModelChain.ProviderCall<>() {
            @Override
            public AdviceResult<T> call(ModelChain.Turn turn) throws ModelChain.UnusableReply {
                return converse(turn, bundle, form, exchanges);
            }

            @Override
            public void passedOver(ChatProvider provider, String reason) {
                exchanges.add(new ModelExchange(1, request(form, opening(bundle)),
                        NullNode.getInstance(), List.of(reason), provider.name(),
                        provider.model()));
            }
        };
        return switch (chain.call(ModelChain.CallSite.ADVICE, turns)) {
            case ModelChain.Result.Answered<AdviceResult<T>> answered -> answered.value();
            case ModelChain.Result.Exhausted<AdviceResult<T>> exhausted ->
                    new AdviceResult.Unavailable<>(exhausted.reason(), exchanges);
        };
    }

    /**
     * One provider's turn: the first answer and, when it fails its
     * checks, the one correction.
     */
    private <T> AdviceResult<T> converse(ModelChain.Turn turn, EvidenceBundle bundle,
            AdviceForm<T> form, List<ModelExchange> exchanges) throws ModelChain.UnusableReply {
        ChatProvider provider = turn.provider();
        List<Message> conversation = opening(bundle);
        List<String> failures = List.of();
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            JsonNode request = request(form, conversation);
            String reply;
            try {
                reply = turn.timed(() -> provider.chat().prompt().system(form.instructions())
                        .messages(conversation).call().content());
            } catch (RuntimeException e) {
                exchanges.add(exchange(provider, attempt, request, NullNode.getInstance(),
                        List.of(ModelChain.unreachable(e))));
                throw e;
            }
            if (reply == null || reply.isBlank()) {
                String reason = "The model answered with nothing.";
                exchanges.add(exchange(provider, attempt, request, TextNode.valueOf(""),
                        List.of(reason)));
                throw new ModelChain.UnusableReply(reason);
            }
            AdviceCheck.Verdict<T> verdict = form.check(bundle, reply);
            exchanges.add(exchange(provider, attempt, request, response(reply),
                    verdict.failures()));
            if (verdict instanceof AdviceCheck.Verdict.Passed<T> passed) {
                return new AdviceResult.Approved<>(passed.answer(), exchanges);
            }
            failures = verdict.failures();
            if (verdict instanceof AdviceCheck.Verdict.Failed<T> failed
                    && failed.answer().isEmpty() && turn.laterProviderRemains()) {
                throw new ModelChain.UnusableReply(String.join(" ", failures));
            }
            log.info("Advice attempt {} from {} failed its checks: {}", attempt,
                    provider.name(), failures);
            conversation.add(new AssistantMessage(reply));
            conversation.add(new UserMessage(correction(failures)));
        }
        return new AdviceResult.Rejected<>(failures, exchanges);
    }

    private static List<Message> opening(EvidenceBundle bundle) {
        List<Message> conversation = new ArrayList<>();
        conversation.add(new UserMessage(json(bundle)));
        return conversation;
    }

    private static ModelExchange exchange(ChatProvider provider, int attempt, JsonNode request,
            JsonNode response, List<String> failures) {
        return new ModelExchange(attempt, request, response, failures, provider.name(),
                provider.model());
    }

    private static AdviceForm<AdviceAnswer> answerForm(String instructions, AdviceCheck check) {
        return new AdviceForm<>() {
            @Override
            public String instructions() {
                return instructions;
            }

            @Override
            public AdviceCheck.Verdict<AdviceAnswer> check(EvidenceBundle bundle, String reply) {
                return check.check(bundle, reply);
            }
        };
    }

    private static String correction(List<String> failures) {
        StringBuilder text = new StringBuilder("Your answer failed these checks:");
        failures.forEach(failure -> text.append("\n- ").append(failure));
        return text.append("\nReturn the corrected JSON answer.").toString();
    }

    private static JsonNode request(AdviceForm<?> form, List<Message> conversation) {
        ObjectNode request = OttoJson.MAPPER.createObjectNode();
        request.put("promptVersion", form.promptVersion());
        ArrayNode messages = request.putArray("messages");
        messages.addObject().put("role", "system").put("content", form.instructions());
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
