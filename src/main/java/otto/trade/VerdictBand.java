package otto.trade;

/**
 * Which of the three answers a trade gets from one measure: the user's
 * net as a share of the larger of his two sides. The rest-of-season fit
 * and the market value are each banded by this one rule, so the two can
 * be read side by side.
 *
 * @param gap the share in percent, rounded to the one decimal place the
 *        answer prints
 * @param verdict even, slight edge or clear edge
 */
record VerdictBand(double gap, String verdict) {

    static final String EVEN = "even";
    static final String SLIGHT_EDGE = "slight edge";
    static final String CLEAR_EDGE = "clear edge";

    /** Inside this percentage of the larger side, the two sides are the same trade. */
    static final double EVEN_BAND = 5.0;

    /** Past this percentage, the gap is larger than the math's own error. */
    static final double CLEAR_BAND = 15.0;

    /**
     * Bands the user's gain against his cost. The band is read from the
     * rounded gap, so a gap printed as 5.0% is never even in one trade
     * and a slight edge in the next.
     */
    static VerdictBand of(double gain, double cost) {
        double larger = Math.max(gain, cost);
        double gap = larger == 0 ? 0 : Math.round(Math.abs(gain - cost) / larger * 1000) / 10.0;
        String verdict = gap < EVEN_BAND ? EVEN : gap <= CLEAR_BAND ? SLIGHT_EDGE : CLEAR_EDGE;
        return new VerdictBand(gap, verdict);
    }

    boolean clear() {
        return CLEAR_EDGE.equals(verdict);
    }
}
