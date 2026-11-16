package otto.ceiling;

/**
 * The regularized lower incomplete gamma function and its inverse, which
 * give a gamma distribution's CDF and percentiles. The evaluation is the
 * series and continued fraction pair from Numerical Recipes (section
 * 6.2), accurate to about 1e-10, which is far finer than any score Otto
 * quotes.
 */
final class Gamma {

    private static final int MAX_ITERATIONS = 500;
    private static final double EPSILON = 1e-12;
    private static final double TINY = 1e-300;

    private static final int BISECTIONS = 200;

    private static final double[] LANCZOS = {
            76.18009172947146, -86.50532032941677, 24.01409824083091,
            -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5};

    private Gamma() {
    }

    /** Returns P(a, x): the share of a gamma(a, 1) distribution at or below {@code x}. */
    static double lowerRegularized(double a, double x) {
        if (x <= 0) {
            return 0.0;
        }
        return x < a + 1 ? series(a, x) : 1.0 - continuedFraction(a, x);
    }

    /** Returns the {@code x} at which P(a, x) equals {@code p}, for 0 < p < 1. */
    static double inverseLowerRegularized(double a, double p) {
        double low = 0.0;
        double high = Math.max(1.0, a);
        while (lowerRegularized(a, high) < p) {
            high *= 2;
        }
        for (int step = 0; step < BISECTIONS && high - low > EPSILON * high; step++) {
            double middle = (low + high) / 2;
            if (lowerRegularized(a, middle) < p) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return (low + high) / 2;
    }

    private static double series(double a, double x) {
        double term = 1.0 / a;
        double sum = term;
        for (int n = 1; n < MAX_ITERATIONS; n++) {
            term *= x / (a + n);
            sum += term;
            if (Math.abs(term) < Math.abs(sum) * EPSILON) {
                break;
            }
        }
        return sum * Math.exp(-x + a * Math.log(x) - logGamma(a));
    }

    /** Returns Q(a, x) = 1 - P(a, x) by Lentz's method. */
    private static double continuedFraction(double a, double x) {
        double b = x + 1 - a;
        double c = 1 / TINY;
        double d = 1 / b;
        double h = d;
        for (int i = 1; i < MAX_ITERATIONS; i++) {
            double an = -i * (i - a);
            b += 2;
            d = an * d + b;
            if (Math.abs(d) < TINY) {
                d = TINY;
            }
            c = b + an / c;
            if (Math.abs(c) < TINY) {
                c = TINY;
            }
            d = 1 / d;
            double delta = d * c;
            h *= delta;
            if (Math.abs(delta - 1) < EPSILON) {
                break;
            }
        }
        return Math.exp(-x + a * Math.log(x) - logGamma(a)) * h;
    }

    private static double logGamma(double a) {
        double y = a;
        double tmp = a + 5.5;
        tmp -= (a + 0.5) * Math.log(tmp);
        double series = 1.000000000190015;
        for (double coefficient : LANCZOS) {
            series += coefficient / ++y;
        }
        return -tmp + Math.log(2.5066282746310005 * series / a);
    }
}
