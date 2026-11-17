package otto.advice;

import java.util.function.BiFunction;

/**
 * One kind of model-written advice: the instructions the model writes
 * under, and how its reply is read and checked. {@link AdviceWriter}
 * runs every form through the same ask, check and correct-once loop.
 *
 * @param <T> the shape a reply is read into
 */
public interface AdviceForm<T> {

    /**
     * Returns a form from its instructions and its check.
     *
     * @param check reads one raw reply against the bundle it was written
     *        from, as {@link #check} does
     */
    static <T> AdviceForm<T> of(String instructions,
            BiFunction<EvidenceBundle, String, AdviceCheck.Verdict<T>> check) {
        return new AdviceForm<>() {
            @Override
            public String instructions() {
                return instructions;
            }

            @Override
            public AdviceCheck.Verdict<T> check(EvidenceBundle bundle, String reply) {
                return check.apply(bundle, reply);
            }
        };
    }

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
