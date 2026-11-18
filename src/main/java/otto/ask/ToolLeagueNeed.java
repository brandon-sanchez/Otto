package otto.ask;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.support.ToolUtils;

import otto.storage.OttoJson;

/**
 * Whether a chat tool's answer depends on which league it is asked in
 * (ADR-0012), read from the {@link LeagueNeed} each tool declares. With
 * several leagues followed and none chosen, Otto asks which league only
 * when the model calls a tool that needs one.
 */
final class ToolLeagueNeed {

    private final Map<String, LeagueNeed.When> byTool;

    private ToolLeagueNeed(Map<String, LeagueNeed.When> byTool) {
        this.byTool = Map.copyOf(byTool);
    }

    /**
     * Reads the league need of every {@code @Tool} method on the classes.
     *
     * @throws IllegalStateException when a tool declares no
     *         {@link LeagueNeed}
     */
    static ToolLeagueNeed of(Class<?>... toolClasses) {
        Map<String, LeagueNeed.When> byTool = new HashMap<>();
        for (Class<?> toolClass : toolClasses) {
            for (Method method : toolClass.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(Tool.class)) {
                    continue;
                }
                String name = ToolUtils.getToolName(method);
                LeagueNeed need = method.getAnnotation(LeagueNeed.class);
                if (need == null) {
                    throw new IllegalStateException("The chat tool %s declares no @LeagueNeed"
                            .formatted(name));
                }
                byTool.put(name, need.value());
            }
        }
        return new ToolLeagueNeed(byTool);
    }

    /**
     * Returns whether one tool call's answer depends on the league.
     *
     * @param tool the tool's name as the model calls it; a name no tool
     *        declares needs a league, because asking once too often is
     *        the safer mistake
     * @param arguments the call's arguments as the model wrote them, a
     *        JSON object; a call whose arguments cannot be read counts as
     *        needing a league
     */
    boolean needsLeague(String tool, String arguments) {
        LeagueNeed.When when = byTool.getOrDefault(tool, LeagueNeed.When.ALWAYS);
        try {
            return when.needsLeague(OttoJson.MAPPER.readTree(arguments == null ? "{}"
                    : arguments));
        } catch (JsonProcessingException e) {
            return true;
        }
    }
}
