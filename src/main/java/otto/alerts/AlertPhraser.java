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

            Use Telegram-friendly plain text. Keep a single simple alert in
            one compact paragraph. When the alert contains several players,
            recommendations, pros, cons, or waiver targets, put one bullet per item
            using "- ". Put a blank line between the main action and supporting
            details. Do not use tables or headings.
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
        StringBuilder text = new StringBuilder(recommendation.action())
                .append("\n\nConfidence: ")
                .append(recommendation.confidence());
        recommendation.pros().forEach(pro -> text.append("\n- ").append(pro));
        recommendation.cons().forEach(con -> text.append("\n- Watch out: ").append(con));
        return text.toString();
    }
}
