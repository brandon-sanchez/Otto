package otto.events;

import java.util.Locale;

/**
 * What one Snapshot Diff event records. The writer stamps it and every
 * detector reads it, so a detector never has to guess from the shape of
 * the facts which events are its own.
 *
 * An event stored before this fact existed carries no kind, and every
 * such event was a status transition: that is what {@link #STATUS}
 * being the default means, and it is why the default lives here rather
 * than in each reader.
 */
public enum DiffKind {

    STATUS,
    TRADE,
    /**
     * A Commissioner Edit that took a player from one roster and put
     * him on another. It is the same fact a trade records and a
     * different sentence, because nobody agreed to it.
     */
    COMMISSIONER,
    DROP,
    /** A player claimed off free agency: the Snipe, when he is watched. */
    ADD;

    private static final String FACT = "kind";

    /** The fact name a diff event stores its kind under. */
    public static String factName() {
        return FACT;
    }

    public String fact() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static DiffKind of(Event event) {
        String kind = event.facts().get(FACT);
        if (kind == null) {
            return STATUS;
        }
        for (DiffKind candidate : values()) {
            if (candidate.fact().equals(kind)) {
                return candidate;
            }
        }
        return STATUS;
    }
}
