package otto.advice;

import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonInclude;

import otto.sleeper.SourceResult;

/**
 * What every Ask tool hands back to the model: the computed facts, or
 * the reason it cannot answer. A tool never guesses and never returns
 * an empty shell, so the model can say plainly what the assistant
 * cannot see instead of filling the gap itself.
 *
 * @param <T> the computed fact shape this tool returns
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolAnswer<T>(T facts, String unavailable) {

    public static <T> ToolAnswer<T> of(T facts) {
        return new ToolAnswer<>(facts, null);
    }

    public static <T> ToolAnswer<T> unavailable(String reason) {
        return new ToolAnswer<>(null, reason);
    }

    /** Returns why a source could not answer, as "source: reason". */
    public static <T> ToolAnswer<T> unavailable(SourceResult.Unavailable<?> unavailable) {
        return unavailable("%s: %s".formatted(unavailable.source(), unavailable.reason()));
    }

    /**
     * Answers from what a source read, or says why the source could not
     * answer.
     *
     * @param answer builds the tool's answer from the source's value
     */
    public static <S, T> ToolAnswer<T> from(SourceResult<S> source,
            Function<S, ToolAnswer<T>> answer) {
        return switch (source) {
            case SourceResult.Ok<S> ok -> answer.apply(ok.value());
            case SourceResult.Unavailable<S> unavailable -> unavailable(unavailable);
        };
    }
}
