package otto.alerts;

import java.util.Map;

import otto.delivery.DeliveryBasis;

/**
 * How one Alert's text is written, and what the outbox matches a retry
 * of it against.
 */
public sealed interface AlertText {

    /** Returns the fingerprint of what the text is written from. */
    DeliveryBasis basis();

    /**
     * Advice the model wrote and {@link otto.advice.AdviceWriter} checked.
     *
     * @param runId the Decision Record that explains it, which is also
     *        its basis: the run id is derived from the evidence
     */
    record Advised(String runId, String text) implements AlertText {

        @Override
        public DeliveryBasis basis() {
            return DeliveryBasis.of(runId);
        }
    }

    /**
     * An Alert the phrasing model words from its Recommendation at send
     * time, which makes no run.
     *
     * @param basis the phrasing's fingerprint and the Alert keys it covers
     * @param facts the facts the phrasing is given
     */
    record Phrased(DeliveryBasis basis, Map<String, String> facts,
            Recommendation recommendation) implements AlertText {
    }
}
