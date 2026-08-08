package otto.sleeper;

/**
 * The typed outcome of one data-source call. A broken or drifted feed
 * yields {@link Unavailable}; it never throws into the Check pipeline.
 */
public sealed interface SourceResult<T> {

    record Ok<T>(T value) implements SourceResult<T> {
    }

    record Unavailable<T>(String source, String reason) implements SourceResult<T> {
    }

    default <R> SourceResult<R> flatMap(java.util.function.Function<T, SourceResult<R>> mapper) {
        return switch (this) {
            case Ok<T> ok -> mapper.apply(ok.value());
            case Unavailable<T> unavailable ->
                new Unavailable<>(unavailable.source(), unavailable.reason());
        };
    }
}
