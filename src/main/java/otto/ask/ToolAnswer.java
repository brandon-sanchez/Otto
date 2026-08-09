package otto.ask;

import com.fasterxml.jackson.annotation.JsonInclude;

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
}
