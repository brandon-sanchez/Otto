package otto.delivery;

import java.util.List;

/**
 * The fingerprint of what an Alert's text was written from. A retry
 * whose basis equals a pending delivery's sends that delivery's text
 * as it stands, so it costs no model call.
 *
 * @param value the fingerprint as stored with a pending delivery
 */
public record DeliveryBasis(String value) {

    private static final String SEPARATOR = "+";

    /** Returns the basis whose fingerprint is this value. */
    public static DeliveryBasis of(String value) {
        return new DeliveryBasis(value);
    }

    /**
     * Returns this basis extended by more inputs, so text written from
     * the same fingerprint with different inputs alongside it, such as
     * other Alert keys or held advice runs, counts as a new delivery.
     * No inputs leaves the basis unchanged.
     */
    public DeliveryBasis plus(List<String> inputs) {
        return inputs.isEmpty()
                ? this
                : new DeliveryBasis(value + SEPARATOR + String.join(SEPARATOR, inputs));
    }
}
