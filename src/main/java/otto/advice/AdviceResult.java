package otto.advice;

import java.util.List;

import otto.records.ModelExchange;
import otto.records.Versions;

/**
 * How one attempt to get checked advice from the model ended. Every
 * outcome carries each model call made, for the Decision Record.
 *
 * @param <T> the shape an approved answer is read into
 */
public sealed interface AdviceResult<T> {

    /** What {@link #model()} returns when no model answered. */
    String NO_MODEL = Versions.NONE;

    List<ModelExchange> exchanges();

    /**
     * Returns the id of the model that wrote the answer, approved or
     * not, or {@link #NO_MODEL} when no provider answered.
     */
    default String model() {
        return this instanceof Unavailable<T> ? NO_MODEL : exchanges().getLast().model();
    }

    /** An answer that passed every check, on the first try or the correction. */
    record Approved<T>(T answer, List<ModelExchange> exchanges) implements AdviceResult<T> {

        public Approved {
            exchanges = List.copyOf(exchanges);
        }
    }

    /** Both answers failed the checks; the failures are the second answer's. */
    record Rejected<T>(List<String> failures, List<ModelExchange> exchanges)
            implements AdviceResult<T> {

        public Rejected {
            failures = List.copyOf(failures);
            exchanges = List.copyOf(exchanges);
        }
    }

    /** No provider in the model chain gave an answer that could be read. */
    record Unavailable<T>(String reason, List<ModelExchange> exchanges)
            implements AdviceResult<T> {

        public Unavailable {
            exchanges = List.copyOf(exchanges);
        }
    }
}
