package otto.advice;

import java.util.List;

import otto.records.ModelExchange;

/**
 * How one attempt to get checked advice from the model ended. Every
 * outcome carries each model call made, for the Decision Record.
 *
 * @param <T> the shape an approved answer is read into
 */
public sealed interface AdviceResult<T> {

    List<ModelExchange> exchanges();

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

    /** The model could not be reached or answered with nothing. */
    record Unavailable<T>(String reason, List<ModelExchange> exchanges)
            implements AdviceResult<T> {

        public Unavailable {
            exchanges = List.copyOf(exchanges);
        }
    }
}
