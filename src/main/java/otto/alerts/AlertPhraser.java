package otto.alerts;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import otto.model.ModelChain;
import otto.storage.OttoJson;

/**
 * Words a Recommendation as the outbound Alert text: one single-shot
 * LLM call with no tools, through the {@link ModelChain}. The pipeline
 * hands the model computed JSON facts; the model phrases them and never
 * alters them. On any failure a deterministic fallback text carries the
 * same facts, so an Alert always goes out.
 */
@Component
public class AlertPhraser {

    private static final String SYSTEM = """
            You are Otto, a fantasy football assistant texting your one user.
            Phrase the computed recommendation below as a short Telegram alert,
            2-5 lines. Lead with the action. When something is uncertain,
            say exactly what is uncertain, using the pros and cons; never
            write a confidence label. Introduce players by full name. Use
            only the facts given. Never invent numbers, names, or reasons.
            When the event includes latestReport, it is the newest injury,
            availability or roster report, read just now: mention it and
            credit latestReportSource.

            Use Telegram-friendly plain text. An IR eligibility alert starts
            with ----- IR UPDATE -----. An alert that requires a healthy player
            to leave IR starts with ----- IR ACTION NEEDED -----. Put each
            recommended roster move on its own numbered line. When the facts
            include proposedRoster, print that complete roster after the moves.
            For other alerts,
            use one compact paragraph and "- " bullets for parallel details.
            Put a blank line between the action and supporting details. Never use
            a Markdown table.
            """;

    private static final Logger log = LoggerFactory.getLogger(AlertPhraser.class);

    private final ModelChain chain;

    public AlertPhraser(ModelChain chain) {
        this.chain = chain;
    }

    /**
     * Returns the Alert text for a Recommendation, phrased by the first
     * provider in the model chain that answers, or the deterministic
     * fallback text when none does.
     */
    public String phrase(Map<String, String> eventFacts, Recommendation recommendation) {
        String facts;
        try {
            facts = OttoJson.MAPPER.writeValueAsString(Map.of(
                    "event", eventFacts,
                    "recommendation", recommendation));
        } catch (JsonProcessingException e) {
            log.warn("Cannot write Alert facts, using deterministic fallback", e);
            return fallback(recommendation);
        }
        return switch (chain.call(ModelChain.CallSite.PHRASING, turn -> {
            String content = turn.timed(() -> turn.provider().chat().prompt().system(SYSTEM)
                    .user(facts).call().content());
            if (content == null || content.isBlank()) {
                throw new ModelChain.UnusableReply("The model answered with nothing.");
            }
            return content;
        })) {
            case ModelChain.Result.Answered<String> answered -> answered.value();
            case ModelChain.Result.Exhausted<String> exhausted -> {
                log.warn("LLM phrasing failed, using deterministic fallback: {}",
                        exhausted.reason());
                yield fallback(recommendation);
            }
        };
    }

    private String fallback(Recommendation recommendation) {
        StringBuilder text = new StringBuilder(recommendation.action()).append('\n');
        recommendation.pros().forEach(pro -> text.append("\n- ").append(pro));
        recommendation.cons().forEach(con -> text.append("\n- Watch out: ").append(con));
        return text.toString();
    }
}
