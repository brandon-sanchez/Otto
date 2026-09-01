package otto.ask;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

            Use Telegram-friendly plain text. Ordinary factual answers stay
            conversational. Structured answers follow these exact contracts.

            A roster always shows the complete roster in league slot order:
            ----- STARTERS -----
            QB: Player Name
            ----- BENCH -----
            RB: Player Name
            ----- IR -----
            Empty
            Include IR or TAXI only when the tool returns that section. An empty
            returned section says Empty. Hide ACTIVE health labels; append other
            health labels in parentheses. Use the same roster format for every
            manager.

            A lineup recommendation leads with the recommendation and projected
            gain, then shows the complete resulting STARTERS, BENCH, IR and TAXI
            roster. Show the complete roster even when no swap is recommended.
            Mark each changed player on a separate short line so Telegram wrapping
            cannot detach it from a long name:
            FLEX: Player Name
            ↳ Move to starter
            WR: Other Player
            ↳ Move to bench

            An injury-only question lists potentially playable PROBABLE and
            QUESTIONABLE players first under ----- MAY PLAY -----, then players
            who cannot play under ----- OUT / IR -----. Omit healthy players and
            empty sections. If none are injured, say so in one sentence.

            A comparison uses ----- COMPARISON -----, one line per player, then
            Recommendation and Difference lines. Waiver answers use
            ----- WAIVER TARGETS ----- and default to the best three. Every target
            includes Recommended Drop. Say "Recommended Drop: None - open roster
            spot available" when no drop is needed. Otherwise name the drop and
            explain what the target does better, including consistency or evidence
            of an improving role when the tool supplied it.

            A trade answer starts with ----- TRADE VERDICT -----, lists what the
            user receives and gives, and shows starting-lineup impact. Then show
            both complete post-trade rosters under ----- YOUR ROSTER AFTER TRADE -----
            and ----- OTHER TEAM AFTER TRADE -----. If the tool cannot construct
            both rosters confidently, withhold the verdict and state why.

            Standings use ----- STANDINGS ----- and include the full league in seed
            order, marking the user's team with "<-- You". Lists of settings and
            watchlist entries use labeled headings. A single setting change stays
            conversational. Never use a Markdown table.
            """;

    private static final String BRIEF = """
            Keep ordinary answers to 2 to 5 lines. Keep each line short. Structured formats may
            use as many lines as their complete sections require. Lead with the
            recommendation or direct answer, then give only the most useful reason.
            He will ask "why" or "more" when he wants the rest.
            """;

    private static final String DEEP = """
            Give the full reasoning this time. Include the numbers your tools
            returned, the alternatives they ranked, and what would change the
            call. Group related details and use bullets when there is more than
            one item. Stay inside the tool results.
            """;

    /** What the user gets when the model cannot be reached at all. */
    private static final String UNREACHABLE = """
            I cannot reach my language model right now, so I cannot answer \
            that. My checks and alerts keep running, and I will answer once \
            it is back.""";

    /** What the user gets when the model answers with nothing. */
    private static final String EMPTY_ANSWER = """
            My language model came back with nothing that time. Ask me \
            again and I will have another go.""";

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

    /** One run of the loop: the answer, or the failure to own up to. */
    private sealed interface Outcome {

        record Answered(String text) implements Outcome {
        }

        record Failed(String reply) implements Outcome {
        }
    }

    /**
     * Answers one Ask and records the exchange in the rolling window.
     * A failure reply is not an answer, so it is never recorded: a
     * spell of them would otherwise evict the real conversation and
     * come back to the model as its own prior words.
     *
     * @return the reply text to send back to the chat
     */
    public String answer(String question) {
        Instant now = clock.instant();
        return switch (run(question, now)) {
            case Outcome.Answered answered -> {
                conversation.record(now, question, answered.text());
                yield answered.text();
            }
            case Outcome.Failed failed -> failed.reply();
        };
    }

    /**
     * The two failures are told apart on purpose: a model that never
     * answered is a different thing from one that answered with
     * nothing, and claiming the wrong one would be a lie about what
     * the assistant can see.
     */
    private Outcome run(String question, Instant now) {
        try {
            String content = chat.prompt()
                    .messages(prompt(question, now))
                    .tools(tools)
                    .call()
                    .content();
            if (content == null || content.isBlank()) {
                log.warn("The model answered with no content");
                return new Outcome.Failed(EMPTY_ANSWER);
            }
            return new Outcome.Answered(content);
        } catch (Exception e) {
            // A wrapped interrupt must not die here: restore the flag
            // so whatever runs this thread next still sees it.
            if (e instanceof InterruptedException
                    || e.getCause() instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Ask loop failed, answering with the outage reply", e);
            return new Outcome.Failed(UNREACHABLE);
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
