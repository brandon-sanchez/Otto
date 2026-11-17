package otto.team;

import java.util.Locale;
import java.util.Optional;

/** Which way a team is headed: playing to win now, rebuilding, or between the two. */
public enum Direction {
    CONTENDING("contending"),
    MIDDLE("in the middle"),
    REBUILDING("rebuilding");

    private final String words;

    Direction(String words) {
        this.words = words;
    }

    /** Returns the direction as an answer says it, such as "in the middle". */
    public String words() {
        return words;
    }

    /**
     * Reads a direction the user named, such as "contending", "middle"
     * or "rebuild".
     *
     * @return empty for anything else
     */
    public static Optional<Direction> named(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String word = text.strip().toLowerCase(Locale.ROOT);
        if (word.startsWith("contend")) {
            return Optional.of(CONTENDING);
        }
        if (word.startsWith("rebuild")) {
            return Optional.of(REBUILDING);
        }
        if (word.equals("middle") || word.equals("in the middle")) {
            return Optional.of(MIDDLE);
        }
        return Optional.empty();
    }
}
