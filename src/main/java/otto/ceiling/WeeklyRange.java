package otto.ceiling;

import java.util.Map;
import java.util.Optional;

/**
 * How one player's weekly scores spread around his projection: a gamma
 * distribution with the projection as its mean and a spread that grows
 * with it. Weekly scores are right-skewed, so most weeks sit a little
 * below the projection and a long tail runs to the big weeks; a gamma
 * fits the 2019-2025 weekly PPR scores better than a normal or a
 * lognormal at every position.
 *
 * <p>The spread is the weekly standard deviation as a straight line in
 * the projection, fitted per position on 2019-2025 player seasons of 8
 * or more games. The RB, WR and TE lines are the buy-low and sell-high
 * research note's; the QB line was fitted on the same data.
 */
public final class WeeklyRange {

    private record Spread(double slope, double base) {

        double at(double projection) {
            return slope * projection + base;
        }
    }

    private static final Map<String, Spread> SPREADS = Map.of(
            "QB", new Spread(0.08, 6.1),
            "RB", new Spread(0.33, 2.9),
            "WR", new Spread(0.35, 2.8),
            "TE", new Spread(0.41, 2.1));

    private final double shape;
    private final double scale;

    private WeeklyRange(double mean, double deviation) {
        this.shape = (mean / deviation) * (mean / deviation);
        this.scale = deviation * deviation / mean;
    }

    /**
     * Returns the range around a projection, or empty when the position
     * has no fitted spread or the projection is not above zero.
     *
     * @param projection the projected points for one game, in league scoring
     */
    public static Optional<WeeklyRange> around(String position, double projection) {
        Spread spread = SPREADS.get(position);
        if (spread == null || !(projection > 0)) {
            return Optional.empty();
        }
        return Optional.of(new WeeklyRange(projection, spread.at(projection)));
    }

    /** Returns the chance, from 0 to 1, that one week scores at least {@code points}. */
    public double chanceOfAtLeast(double points) {
        return points <= 0 ? 1.0 : 1.0 - Gamma.lowerRegularized(shape, points / scale);
    }

    /**
     * Returns the score that a share of weeks falls below.
     *
     * @param fraction the share of weeks, strictly between 0 and 1
     */
    public double percentile(double fraction) {
        if (!(fraction > 0 && fraction < 1)) {
            throw new IllegalArgumentException("A percentile needs a fraction between 0 and 1");
        }
        return Gamma.inverseLowerRegularized(shape, fraction) * scale;
    }
}
