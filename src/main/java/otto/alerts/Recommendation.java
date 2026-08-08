package otto.alerts;

import java.util.List;

/**
 * A computed course of action. Produced by deterministic code;
 * narrated, never altered, by the LLM.
 */
public record Recommendation(
        String playerId,
        String player,
        String action,
        Confidence confidence,
        List<String> pros,
        List<String> cons) {
}
