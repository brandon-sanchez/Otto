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
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import otto.advice.FriendVoice;
import otto.model.ModelChain;

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

            A waiver preference is in force only while
            manage_waiver_preferences reports it, whatever the
            conversation said before.

            Use Telegram-friendly plain text.

            """ + FriendVoice.RULE + """

            Rosters, lineups, standings and the other structured answers below keep
            their exact layouts, slot labels included. Everything you write around
            them to explain follows the voice rule.

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

            A lineup recommendation arrives already written in the
            recommend_lineup message; send it word for word. When you answer
            from it beside other tools, keep the week it names, the games it
            says are left and the players it says are locked.

            An injury-only question lists potentially playable PROBABLE and
            QUESTIONABLE players first under ----- MAY PLAY -----, then players
            who cannot play under ----- OUT / IR -----. Omit healthy players and
            empty sections. If none are injured, say so in one sentence.

            A comparison uses ----- COMPARISON -----, one line per player, then a
            sentence or two on whom to start and by how much. Waiver answers use
            ----- WAIVER TARGETS ----- and default to the best three. For every
            target, say in a sentence whom he should drop to make room, or that he
            has an open roster spot and needs no drop, and explain what the target
            does better, including consistency or evidence of an improving role
            when the tool supplied it.

            A trade answer starts with ----- TRADE VERDICT -----, lists what the
            user receives and gives, and shows starting-lineup impact. Then show
            both complete post-trade rosters under ----- YOUR ROSTER AFTER TRADE -----
            and ----- OTHER TEAM AFTER TRADE -----. If the tool cannot construct
            both rosters confidently, withhold the verdict and state why.

            Standings use ----- STANDINGS ----- and include the full league in seed
            order, marking the user's team with "<-- You". Lists of settings and
            watchlist entries use labeled headings. A team usage answer shows the
            tool's table line for line and adds at most two lines. A ceiling answer
            sends the tool's message word for word and adds nothing. A single setting
            change stays conversational. Never use a Markdown table.
            """;

    private static final String BRIEF = """
            Keep ordinary answers to a few natural sentences, usually two to four.
            Structured formats may use as many lines as their complete sections
            require. Lead with the recommendation or direct answer, then give the
            reason that matters most. He will ask "why" or "more" when he wants the
            rest.
            """;

    private static final String DEEP = """
            Give the full reasoning this time. Include the numbers your tools
            returned, the alternatives they ranked, and what would change the
            call. Write it as short paragraphs with one idea each, and stay inside
            the tool results.
            """;

    /** What the user gets when the model cannot be reached at all. */
    private static final String UNREACHABLE = """
            I can't reach my language model right now, so I can't answer \
            that yet. My checks and alerts keep running, and I'll answer once \
            it's back.""";

    /** What the user gets when the model answers with nothing. */
    private static final String EMPTY_ANSWER = """
            My language model came back empty that time. Ask me again and \
            I'll have another go.""";

    private static final Set<String> DEPTH_WORDS =
            Set.of("why", "more", "detail", "details", "explain", "expand");

    /** A depth word only opens up the last answer inside a short message. */
    private static final int DEPTH_PHRASE_WORDS = 4;

    private static final Logger log = LoggerFactory.getLogger(AskService.class);

    private final ModelChain chain;
    private final List<ToolCallback> toolCallbacks;
    private final ToolCallingManager toolCalling;
    private final ConversationStore conversation;
    private final Clock clock;

    public AskService(ModelChain chain, LineupTools lineupTools, PlayerTools playerTools,
            TradeTools tradeTools, TeamTools teamTools, SettingsTools settingsTools,
            ToolCallingManager toolCalling, ConversationStore conversation, Clock clock) {
        this.chain = chain;
        this.toolCallbacks = List.of(ToolCallbacks.from(lineupTools, playerTools, tradeTools,
                teamTools, settingsTools));
        this.toolCalling = toolCalling;
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
        List<Message> messages = prompt(question, now);
        return switch (chain.call(ModelChain.CallSite.ASK, turn -> {
            Optional<String> content = turn.timed(() -> converse(turn.provider().chat(), messages))
                    .filter(text -> !text.isBlank());
            if (content.isEmpty()) {
                throw new ModelChain.UnusableReply("The model answered with no content.");
            }
            return content.get();
        })) {
            case ModelChain.Result.Answered<String> answered ->
                    new Outcome.Answered(answered.value());
            case ModelChain.Result.Exhausted<String> exhausted -> {
                log.warn("Ask loop failed: {}", exhausted.reason());
                yield new Outcome.Failed(exhausted.anyReached() ? EMPTY_ANSWER : UNREACHABLE);
            }
        };
    }

    /**
     * Spring AI's tool-calling advisor can return a tool's result without
     * a second model reply only for a tool marked {@code returnDirect},
     * a fixed setting that applies to every round. Whether a checked
     * answer goes out as it is depends on what else the model called, so
     * the loop runs here.
     */
    private Optional<String> converse(ChatClient chat, List<Message> start) {
        List<Message> history = start;
        boolean firstRound = true;
        while (true) {
            ChatResponse response = chat.prompt()
                    .messages(history)
                    .tools(toolCallbacks)
                    .advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
                    .call()
                    .chatResponse();
            if (response == null || !response.hasToolCalls()) {
                return Optional.ofNullable(response)
                        .map(reply -> reply.getResult().getOutput().getText());
            }
            CheckedAnswers round = CheckedAnswers.forRound(
                    firstRound && toolCallCount(response) == 1);
            ToolExecutionResult executed = toolCalling.executeToolCalls(
                    new Prompt(history, ToolCallingChatOptions.builder()
                            .toolCallbacks(toolCallbacks)
                            .toolContext(round.toolContext())
                            .build()),
                    response);
            Optional<String> checked = round.answerToSendAsIs();
            if (checked.isPresent()) {
                return checked;
            }
            history = executed.conversationHistory();
            firstRound = false;
        }
    }

    private static long toolCallCount(ChatResponse response) {
        return response.getResults().stream()
                .mapToLong(generation -> generation.getOutput().getToolCalls().size())
                .sum();
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
