package otto.nflverse;

import java.util.List;
import java.util.Set;

/** What every nflverse reader agrees on about the rows it streams. */
final class FeedRows {

    static final Set<String> KEPT_POSITIONS = Set.of("QB", "RB", "WR", "TE");
    static final String REGULAR_SEASON = "REG";

    private FeedRows() {
    }

    /**
     * Fails the whole download when the file no longer carries a column
     * this code reads. These feeds gain and reorder columns between
     * seasons, which a name-keyed read survives - but a renamed column
     * would read as a blank field and quietly price a week at zero. The
     * client turns the throw into a typed unavailable result, so drift
     * self-reports instead.
     */
    static void requireColumns(Csv.Row row, Set<String> required) {
        List<String> missing = required.stream()
                .filter(column -> !row.columns().containsKey(column))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("schema drift: missing columns " + missing);
        }
    }

    /** The published files write a missing value as the string "NA". */
    static boolean blankOrNa(String value) {
        return value.isBlank() || "NA".equals(value);
    }
}
