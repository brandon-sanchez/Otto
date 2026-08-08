package otto.alerts;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import otto.storage.OttoJson;

/**
 * Words a Recommendation as the outbound Alert text: one single-shot
 * LLM call with no tools. The pipeline hands the model computed JSON
 * facts; the model phrases them and never alters them. On any failure
 * a deterministic fallback text carries the same facts, so an Alert
 * always goes out.
 */
@Component
public class AlertPhraser {

    private static final String SYSTEM = """
            You are Otto, a fantasy football assistant texting your one user.
            Phrase the computed recommendation below as a short Telegram alert,
            2-5 lines. State the action when confidence is HIGH; voice the
            doubt with the pros and cons when it is MEDIUM. Use only the
            facts given. Never invent numbers, names, or reasons.
            """;

    private static final Logger log = LoggerFactory.getLogger(AlertPhraser.class);

    private final ChatClient chat;

    public AlertPhraser(ChatClient.Builder builder) {
        this.chat = builder.build();
    }

    public String phrase(Map<String, String> eventFacts, Recommendation recommendation) {
        try {
            String facts = OttoJson.MAPPER.writeValueAsString(Map.of(
                    "event", eventFacts,
                    "recommendation", recommendation));
            String content = chat.prompt().system(SYSTEM).user(facts).call().content();
            if (content == null || content.isBlank()) {
                return fallback(recommendation);
            }
            return content;
        } catch (Exception e) {
            log.warn("LLM phrasing failed, using deterministic fallback", e);
            return fallback(recommendation);
        }
    }

    private String fallback(Recommendation recommendation) {
        return "%s. Confidence: %s. Pros: %s. Cons: %s.".formatted(
                recommendation.action(),
                recommendation.confidence(),
                String.join("; ", recommendation.pros()),
                String.join("; ", recommendation.cons()));
    }
}
