package otto.alerts;

/**
 * The checked text of one Alert, and what the outbox matches a retry of
 * it against.
 */
public sealed interface AlertText {

    /** Returns the text the user reads. */
    String text();

    /**
     * Returns the fingerprint of what the text was written from, which
     * {@link AlertDeliveryOutbox#pendingText} matches a retry against.
     */
    String basis();

    /**
     * Advice the model wrote and {@link otto.advice.AdviceWriter} checked.
     *
     * @param runId the Decision Record that explains it, which is also
     *        its basis: the run id is derived from the evidence
     */
    record Advised(String runId, String text) implements AlertText {

        @Override
        public String basis() {
            return runId;
        }
    }

    /**
     * An Alert phrased from its Recommendation, which makes no run.
     *
     * @param basis the phrasing's fingerprint and the Alert keys it covers
     */
    record Phrased(String text, String basis) implements AlertText {
    }
}
