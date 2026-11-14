package otto.advice;

/**
 * One kind of model-written advice: the instructions the model writes
 * under, and how its reply is read and checked. {@link AdviceWriter}
 * runs every form through the same ask, check and correct-once loop.
 *
 * @param <T> the shape a reply is read into
 */
public interface AdviceForm<T> {

    /** The system instructions for every call made under this form. */
    String instructions();

    /**
     * Reads one raw reply and checks it against the bundle it was
     * written from. Each failure is written for the model, because the
     * correction attempt hands the failures back to it.
     */
    AdviceCheck.Verdict<T> check(EvidenceBundle bundle, String reply);

    /** Identifies the instructions, for the Decision Record. */
    default String promptVersion() {
        return Sha256.hex(instructions()).substring(0, 12);
    }
}
