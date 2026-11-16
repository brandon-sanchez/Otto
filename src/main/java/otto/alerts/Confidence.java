package otto.alerts;

/**
 * The confidence gate. LOW is never proactive. HIGH marks a certainty,
 * such as a ruled-out starter or an empty slot, and MEDIUM an estimate;
 * when candidates merge into one Alert, the more certain one leads. No
 * message shows the label: an uncertain Alert names what is uncertain.
 */
public enum Confidence {
    HIGH, MEDIUM, LOW
}
