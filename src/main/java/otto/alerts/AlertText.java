package otto.alerts;

import java.util.Map;

/** How one Alert's text is written. */
public sealed interface AlertText {

    /**
     * Advice the model wrote and {@link otto.advice.AdviceWriter} checked.
     *
     * @param runId the Decision Record that explains it
     */
    record Advised(String runId, String text) implements AlertText {
    }

    /**
     * An Alert the phrasing model words from its Recommendation at send
     * time, which makes no run.
     *
     * @param facts the facts the phrasing is given
     */
    record Phrased(Map<String, String> facts, Recommendation recommendation)
            implements AlertText {
    }
}
