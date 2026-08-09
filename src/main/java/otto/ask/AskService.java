package otto.ask;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

/**
 * Lane A: the Ask loop. Free text goes to a tool-calling agent loop
 * over the deterministic tools, and the model narrates what they
 * computed. The model owns the language and nothing else - every fact
 * and every number reaches it from Java.
 *
 * Replies stay short by default. The user opens them up by asking
 * "why" or "more", which is the only depth control the chat needs.
 */
@Component
public class AskService {

    private static final String SYSTEM = """
            You are Otto, a fantasy football assistant texting your one
            user about his Sleeper league team.

            Every fact and every number comes from your tools. Call a tool
            before you answer anything about his roster, his lineup or the
            league, and repeat only what it returned. Never compute,
            estimate or invent a projection, a point total, a matchup or a
            name. When a tool reports it cannot answer, say plainly what
            you cannot see rather than filling the gap.

            He makes every roster change himself in the Sleeper app, so
            recommend and never claim to have acted.
            """;

    private static final String BRIEF = """
            Answer in 2 to 5 lines: the recommendation, then a one-line
            reason. He will ask "why" or "more" when he wants the rest.
            """;

    private static final String DEEP = """
            Give the full reasoning this time: the numbers your tools
            returned, the alternatives they ranked, and what would change
            the call. Stay inside the tool results.
            """;

    /** What the user gets when the model cannot answer at all. */
    private static final String UNREACHABLE = """
            I cannot reach my language model right now, so I cannot answer \
            that. My checks and alerts keep running, and I will answer once \
            it is back.""";

    private static final Set<String> DEPTH_WORDS =
            Set.of("why", "more", "detail", "details", "explain", "expand");

    /** A depth word only opens up the last answer inside a short message. */
    private static final int DEPTH_PHRASE_WORDS = 4;

    private static final Logger log = LoggerFactory.getLogger(AskService.class);

    private final ChatClient chat;
    private final AskTools tools;
    private final ConversationStore conversation;
    private final Clock clock;

    public AskService(ChatClient.Builder builder, AskTools tools,
            ConversationStore conversation, Clock clock) {
        this.chat = builder.build();
        this.tools = tools;
        this.conversation = conversation;
        this.clock = clock;
    }

    /**
     * Answers one Ask and records the exchange in the rolling window.
     * An outage reply is not an answer, so it is never recorded: a
     * spell of them would otherwise evict the real conversation and
     * come back to the model as its own prior words.
     *
     * @return the reply text to send back to the chat
     */
    public String answer(String question) {
        Instant now = clock.instant();
        Optional<String> reply = run(question, now);
        reply.ifPresent(answer -> conversation.record(now, question, answer));
        return reply.orElse(UNREACHABLE);
    }

    /** Empty when the model gave nothing back. */
    private Optional<String> run(String question, Instant now) {
        try {
            String content = chat.prompt()
                    .messages(prompt(question, now))
                    .tools(tools)
                    .call()
                    .content();
            return content == null || content.isBlank() ? Optional.empty() : Optional.of(content);
        } catch (Exception e) {
            log.warn("Ask loop failed, answering with the outage reply", e);
            return Optional.empty();
        }
    }

    private List<Message> prompt(String question, Instant now) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM + (wantsDepth(question) ? DEEP : BRIEF)));
        for (ConversationStore.Turn turn : conversation.window(now)) {
            messages.add(switch (turn.role()) {
                case USER -> new UserMessage(turn.text());
                case ASSISTANT -> new AssistantMessage(turn.text());
            });
        }
        messages.add(new UserMessage(question));
        return messages;
    }

    /**
     * True when the message is the user opening up the last answer:
     * "why", "more", "tell me more". A question that starts with "why"
     * counts however long it runs; otherwise only a short message does,
     * so "I want more points from my flex" stays a fresh question.
     */
    private boolean wantsDepth(String question) {
        String normalized = question.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z ]", " ")
                .replaceAll(" +", " ")
                .trim();
        if (normalized.equals("why") || normalized.startsWith("why ")) {
            return true;
        }
        List<String> words = List.of(normalized.split(" "));
        return words.size() <= DEPTH_PHRASE_WORDS
                && words.stream().anyMatch(DEPTH_WORDS::contains);
    }
}
