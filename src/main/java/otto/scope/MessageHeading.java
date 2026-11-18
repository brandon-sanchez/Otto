package otto.scope;

import java.util.Optional;

/**
 * The line {@link TelegramClient} puts at the top of every message it
 * sends right now, such as the name of the league the message is about.
 */
@FunctionalInterface
public interface MessageHeading {

    /** Returns the heading line for a message sent now, or empty for none. */
    Optional<String> current();
}
