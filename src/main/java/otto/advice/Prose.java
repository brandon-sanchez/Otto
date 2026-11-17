package otto.advice;

import java.util.Collection;
import java.util.List;

/** How Otto's sentences join several items. */
public final class Prose {

    private Prose() {
    }

    /** Joins items the way a sentence lists them: "A", "A and B", "A, B and C". */
    public static String listed(Collection<String> items) {
        return listed(items, "and");
    }

    /**
     * Joins items with the conjunction the sentence needs, such as "A, B
     * or C". No items join to an empty string.
     */
    public static String listed(Collection<String> items, String conjunction) {
        List<String> values = List.copyOf(items);
        if (values.size() <= 1) {
            return String.join("", values);
        }
        return "%s %s %s".formatted(String.join(", ", values.subList(0, values.size() - 1)),
                conjunction, values.getLast());
    }
}
