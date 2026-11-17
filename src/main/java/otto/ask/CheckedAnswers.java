package otto.ask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.ai.chat.model.ToolContext;

import otto.advice.AnswerDelivery;
import otto.advice.ToolAnswer;

import otto.advice.ToolAnswer;

/**
 * The checked answers the chat tools of one round of tool calls hand
 * back, and whether such an answer goes to the user unchanged.
 *
 * <p>A checked answer is text a tool built and checked itself, such as
 * an explanation that passed the advice check or a table rendered from
 * computed figures. The chat model must not write it a second time,
 * because its reply is never checked again. {@link AskService} sends a
 * checked answer as it is when it is the only tool call the model made
 * for the question. When the model calls other tools first or beside it,
 * the answer goes back to the model like any tool result and the model
 * writes the reply, so the user may read the checked text reworded.
 *
 * <p>One instance serves one round and reaches each tool through
 * Spring AI's {@link ToolContext}. The tools of a round run one after
 * another, so the instance needs no locking.
 */
public final class CheckedAnswers {

    private static final String KEY = CheckedAnswers.class.getName();

    private final AnswerDelivery delivery;
    private final List<String> offered = new ArrayList<>();

    private CheckedAnswers(AnswerDelivery delivery) {
        this.delivery = delivery;
    }

    static CheckedAnswers forRound(boolean onlyToolCall) {
        return new CheckedAnswers(onlyToolCall ? AnswerDelivery.UNCHANGED
                : AnswerDelivery.THROUGH_CHAT_MODEL);
    }

    /** Returns the round's answers from the context a tool was called with. */
    public static CheckedAnswers of(ToolContext context) {
        if (context.getContext().get(KEY) instanceof CheckedAnswers answers) {
            return answers;
        }
        throw new IllegalStateException("A chat tool was called outside an Ask round.");
    }

    /** Returns how a checked answer from this round reaches the user. */
    public AnswerDelivery delivery() {
        return delivery;
    }

    /**
     * Marks a tool's result as a final, checked answer and returns it
     * unchanged. A result that carries no facts is not an answer, so the
     * chat model still explains why the tool could not answer.
     *
     * @param text the text the user reads, taken from the result's facts
     */
    public <T> ToolAnswer<T> offer(ToolAnswer<T> answer, Function<T, String> text) {
        if (answer.facts() != null) {
            offered.add(text.apply(answer.facts()));
        }
        return answer;
    }

    Map<String, Object> toolContext() {
        return Map.of(KEY, this);
    }

    Optional<String> answerToSendAsIs() {
        return delivery == AnswerDelivery.UNCHANGED && offered.size() == 1
                ? Optional.of(offered.getFirst())
                : Optional.empty();
    }
}
