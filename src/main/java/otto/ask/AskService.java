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
 *
 * <p>With several leagues followed, the loop can start before a league
 * is chosen. It answers what is the same in every league and stops,
 * running no tool, as soon as the model calls a tool whose answer
 * depends on the league ({@link ToolLeagueNeed}).
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

            Write Telegram HTML: plain lines, with <b>bold</b> for the fact he asked
            for. Never write Markdown or a table.

            """ + FriendVoice.RULE + """

            Rosters, lineups, standings and the other structured answers below keep
            their exact layouts, slot labels included. Whatever you write around them
            follows the voice rule and stays to one or two short lines.

            A roster always shows the complete roster in league slot order:
            ----- STARTERS -----
            QB: Player Name
            ----- BENCH -----
            RB: Player Name 🟡 Q
            ----- IR -----
            Empty
            Include IR or TAXI only when the tool returns that section. An empty
            returned section says Empty. Show nothing beside a healthy or probable
            player; after anyone else put his status as a dot and short tag: 🟡 Q for
            questionable, 🟠 D for doubtful, 🔴 O for out, 🔴 IR, 🔴 PUP, 🔴 SUS for
            suspended, and NA with no dot. Use the same roster format for every manager.

            A lineup recommendation arrives already written in the
            recommend_lineup message; send it word for word. When you answer
            from it beside other tools, keep the week it names, the games it
            says are left and the players it says are locked.

            An injury-only question lists potentially playable PROBABLE and
            QUESTIONABLE players first under ----- MAY PLAY -----, then players
            who cannot play under ----- OUT / IR -----, each with his status tag.
            Omit healthy players and empty sections. If none are injured, say so in
            one sentence.

            A comparison uses ----- COMPARISON -----, one line per player with his
            projection in bold, then one short line on whom to start and by how much.
            Waiver answers default to the best three, numbered, each as
            "1. <b>Player Name</b> · RB, TB", then a line "➖ Drop: Player Name", or
            "No drop needed, you have an open spot", then one short line on what he
            does better, such as consistency or an improving role when the tool
            supplied it.

            A trade answer starts with the tool's "headline" word for word on its own
            line; with no headline, it gives no verdict. Then "You give" and "You get",
            each followed by one player per line, and one line per side on what it
            does to the starting lineup, with 🟢 when it gains points and 🔴 when it
            loses them. Then both complete post-trade
            rosters under ----- YOUR ROSTER AFTER TRADE ----- and
            ----- OTHER TEAM AFTER TRADE -----. If the tool cannot construct both
            rosters confidently, withhold the verdict and state why.

            Standings use ----- STANDINGS ----- and include the full league in seed
            order, one team per line, with the user's team in bold. A playoff race
            shows each team with its status: ✅ Clinched, 🟡 In the hunt or
            ❌ Eliminated. Lists of settings and watchlist entries use labeled
            headings. A team usage answer shows the tool's table line for line and adds
            at most two lines. A ceiling answer sends the tool's message word for word
            and adds nothing. A single setting change stays conversational, in plain
            words with no dots.
            """;

    private static final String BRIEF = """
            Keep ordinary answers short: the direct answer or recommendation on the
            first line, then at most one or two short lines of reason, one fact to a
            line. Never write a paragraph. Structured formats may use as many lines as
            their complete sections require. He will ask "why" or "more" when he wants
            the rest.
            """;

    private static final String DEEP = """
            Give the full reasoning this time. Include the numbers your tools
            returned, the alternatives they ranked, and what would change the
            call. Write it as short paragraphs with one idea each, and stay inside
            the tool results.
            """;

    private static final String NO_LEAGUE_CHOSEN = """

            He plays in more than one league and has not said which one this
            question is about. What a player or an NFL team did on the field is
            the same in every league, such as usage, stats, ranks and news: answer
            that from its tool as usual. Anything about his team or a league
            depends on the league, and so do fantasy points, which each league
            scores its own way. For those, call the tool as usual, putting "fantasy
            points" in what he asked about when the question is about them, and
            Otto asks him which league. Never answer such a question without its
            tool, and never ask him which league yourself.
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

    /** What one Ask came to. */
    public sealed interface Reply {

        /** A reply to send. */
        record Answered(String text, About about) implements Reply {
        }

        /** The answer depends on a league and none is chosen, so nothing was answered. */
        record LeagueNeeded() implements Reply {
        }
    }

    /** What a reply is about, which decides whether it names a league. */
    public enum About {
        /** The answer depends on the league in scope. */
        LEAGUE,
        /** The answer is the same in every league. */
        EVERY_LEAGUE,
        /** The reply owns up to a failure and answers nothing. */
        NOTHING
    }

    /** Whether the league is settled before the loop runs. */
    private enum Mode {
        LEAGUE_IN_SCOPE(""), NO_LEAGUE(NO_LEAGUE_CHOSEN);

        private final String instructions;

        Mode(String instructions) {
            this.instructions = instructions;
        }

        List<ConversationStore.Turn> window(ConversationStore conversation, Instant now) {
            return this == LEAGUE_IN_SCOPE ? conversation.window(now)
                    : conversation.windowForEveryLeague(now);
        }

        boolean stopsAt(boolean needsLeague) {
            return this == NO_LEAGUE && needsLeague;
        }
    }

    /** Which chat tools one run of the loop called. */
    private enum ToolUse {
        NONE, SAME_IN_EVERY_LEAGUE, LEAGUE;

        ToolUse and(boolean needsLeague) {
            return needsLeague || this == LEAGUE ? LEAGUE : SAME_IN_EVERY_LEAGUE;
        }
    }

    /** What the model and its tools came to in one run of the loop. */
    private sealed interface Loop {

        /** @param text the reply; null or blank when the model said nothing */
        record Said(String text, ToolUse tools) implements Loop {
        }

        record LeagueNeeded() implements Loop {
        }
    }

    /**
     * Answers one Ask for the league in scope and records the exchange
     * in the rolling window. A failure reply is not an answer, so it is
     * never recorded: a spell of them would otherwise evict the real
     * conversation and come back to the model as its own prior words.
     *
     * <p>Side effects: model calls, whatever the tools it calls do, and
     * one write of the conversation.
     *
     * @return the reply to send back to the chat
     */
    public Reply.Answered answer(String question) {
        if (ask(question, Mode.LEAGUE_IN_SCOPE) instanceof Reply.Answered answered) {
            return answered;
        }
        throw new IllegalStateException("The Ask loop asked for a league while one was in scope.");
    }

    /**
     * Answers one Ask while several leagues are followed and none is
     * chosen. A question whose answer is the same in every league is
     * answered and recorded as {@link #answer} does. A question that
     * depends on a league is left unanswered: the loop stops at the
     * model's first call to a tool that needs a league, before any tool
     * of that round runs, and nothing is recorded.
     *
     * <p>Side effects: model calls, whatever the league-neutral tools it
     * calls do, and one write of the conversation when it answers.
     */
    public Reply answerBeforeLeague(String question) {
        return ask(question, Mode.NO_LEAGUE);
    }

    /**
     * The two failures are told apart on purpose: a model that never
     * answered is a different thing from one that answered with
     * nothing, and claiming the wrong one would be a lie about what
     * the assistant can see.
     */
    private Reply ask(String question, Mode mode) {
        Instant now = clock.instant();
        List<ConversationStore.Turn> window = mode.window(conversation, now);
        return switch (run(prompt(question, window, mode), mode)) {
            case ModelChain.Result.Exhausted<Loop> exhausted -> {
                log.warn("Ask loop failed: {}", exhausted.reason());
                yield new Reply.Answered(exhausted.anyReached() ? EMPTY_ANSWER : UNREACHABLE,
                        About.NOTHING);
            }
            case ModelChain.Result.Answered<Loop>(_, Loop.LeagueNeeded _, _) ->
                    new Reply.LeagueNeeded();
            case ModelChain.Result.Answered<Loop>(_, Loop.Said said, _) -> {
                About about = mode == Mode.LEAGUE_IN_SCOPE ? about(said.tools(), window)
                        : About.EVERY_LEAGUE;
                if (about == About.LEAGUE) {
                    conversation.record(now, question, said.text());
                } else {
                    conversation.recordForEveryLeague(now, question, said.text());
                }
                yield new Reply.Answered(said.text(), about);
            }
        };
    }

    /**
     * A reply that called no tool carries on from the exchange before
     * it, so it is about the league unless that exchange was about every
     * league.
     */
    private static About about(ToolUse tools, List<ConversationStore.Turn> window) {
        return switch (tools) {
            case LEAGUE -> About.LEAGUE;
            case SAME_IN_EVERY_LEAGUE -> About.EVERY_LEAGUE;
            case NONE -> window.isEmpty() || !window.getLast().aboutEveryLeague()
                    ? About.LEAGUE : About.EVERY_LEAGUE;
        };
    }

    private ModelChain.Result<Loop> run(List<Message> messages, Mode mode) {
        return chain.call(ModelChain.CallSite.ASK, turn -> {
            Loop loop = turn.timed(() -> converse(turn.provider().chat(), messages, mode));
            if (loop instanceof Loop.Said said && (said.text() == null || said.text().isBlank())) {
                throw new ModelChain.UnusableReply("The model answered with no content.");
            }
            return loop;
        });
    }

    /**
     * Spring AI's tool-calling advisor can return a tool's result without
     * a second model reply only for a tool marked {@code returnDirect},
     * a fixed setting that applies to every round. Whether a checked
     * answer goes out as it is depends on what else the model called, so
     * the loop runs here.
     */
    private Loop converse(ChatClient chat, List<Message> start, Mode mode) {
        List<Message> history = start;
        ToolUse tools = ToolUse.NONE;
        while (true) {
            ChatResponse response = chat.prompt()
                    .messages(history)
                    .tools(toolCallbacks)
                    .advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
                    .call()
                    .chatResponse();
            if (response == null || !response.hasToolCalls()) {
                return new Loop.Said(response == null ? null
                        : response.getResult().getOutput().getText(), tools);
            }
            List<AssistantMessage.ToolCall> calls = toolCalls(response);
            boolean needsLeague = calls.stream()
                    .anyMatch(call -> ToolLeagueNeed.of(call.name(), call.arguments()));
            if (mode.stopsAt(needsLeague)) {
                return new Loop.LeagueNeeded();
            }
            CheckedAnswers round = CheckedAnswers.forRound(
                    tools == ToolUse.NONE && calls.size() == 1);
            tools = tools.and(needsLeague);
            ToolExecutionResult executed = toolCalling.executeToolCalls(
                    new Prompt(history, ToolCallingChatOptions.builder()
                            .toolCallbacks(toolCallbacks)
                            .toolContext(round.toolContext())
                            .build()),
                    response);
            Optional<String> checked = round.answerToSendAsIs();
            if (checked.isPresent()) {
                return new Loop.Said(checked.get(), tools);
            }
            history = executed.conversationHistory();
        }
    }

    private static List<AssistantMessage.ToolCall> toolCalls(ChatResponse response) {
        return response.getResults().stream()
                .flatMap(generation -> generation.getOutput().getToolCalls().stream())
                .toList();
    }

    private List<Message> prompt(String question, List<ConversationStore.Turn> window,
            Mode mode) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(SYSTEM + (wantsDepth(question) ? DEEP : BRIEF)
                + mode.instructions));
        for (ConversationStore.Turn turn : window) {
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
