package otto.matchup;

/**
 * The weeks of the current season a player ranking draws on, both ends
 * included.
 *
 * @param first the first week counted, 1 or later
 * @param last the last week counted, no earlier than {@code first}
 */
public record WeekSpan(int first, int last) {

    /** Every week of the season. */
    public static final WeekSpan SEASON = new WeekSpan(1, Integer.MAX_VALUE);

    public WeekSpan {
        if (first < 1 || last < first) {
            throw new IllegalArgumentException("weeks %d to %d are not a span".formatted(first,
                    last));
        }
    }

    /** Returns true when the week falls in the span. */
    public boolean contains(int week) {
        return week >= first && week <= last;
    }
}
