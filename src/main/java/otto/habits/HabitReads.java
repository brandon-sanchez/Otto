package otto.habits;

import java.util.Optional;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import otto.directory.DirectoryPlayer;
import otto.sleeper.SourceResult;

/** What every part of the habit capture does with a Sleeper read and a player's details. */
final class HabitReads {

    private static final Logger log = LoggerFactory.getLogger(HabitLedger.class);

    private HabitReads() {
    }

    /**
     * Returns what Sleeper answered, or empty with a warning logged when it
     * could not. The capture carries on without the read, and makes it
     * again on the next update.
     *
     * @param what what was read, as the warning names it
     */
    static <T> Optional<T> read(String what, SourceResult<T> result) {
        return switch (result) {
            case SourceResult.Ok<T> ok -> Optional.of(ok.value());
            case SourceResult.Unavailable<T> unavailable -> {
                log.warn("The habit capture could not read {} from {}: {}", what,
                        unavailable.source(), unavailable.reason());
                yield Optional.empty();
            }
        };
    }

    /**
     * Returns a player's detail from his roster, or from the directory
     * when the roster leaves it blank.
     */
    static Optional<String> known(String fromRoster, DirectoryPlayer fromDirectory,
            Function<DirectoryPlayer, String> field) {
        if (fromRoster != null && !fromRoster.isBlank()) {
            return Optional.of(fromRoster);
        }
        return Optional.ofNullable(fromDirectory).map(field).filter(value -> !value.isBlank());
    }
}
