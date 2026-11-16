package otto.buysell;

/**
 * How far a player's market value moved over about the last month.
 *
 * @param percent the move as a share of where it started, rounded to a
 *        whole percent; negative for a fall
 * @param days how many days the move covers
 * @param source where the move was measured, such as "FantasyCalc's
 *        30-day trend"
 */
public record MarketMove(int percent, int days, String source) {

    /** A move this size or larger is a real change in price rather than drift. */
    static final int MEANINGFUL_PERCENT = 10;

    /** True when the value fell by at least {@link #MEANINGFUL_PERCENT}. */
    public boolean fell() {
        return percent <= -MEANINGFUL_PERCENT;
    }

    /** True when the value rose by at least {@link #MEANINGFUL_PERCENT}. */
    public boolean rose() {
        return percent >= MEANINGFUL_PERCENT;
    }
}
