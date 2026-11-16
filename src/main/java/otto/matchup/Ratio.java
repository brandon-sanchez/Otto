package otto.matchup;

import java.util.OptionalDouble;

/**
 * The one way the matchup analytics divide counts. A ratio over nothing
 * is empty, never zero, so a rate no game measured reads as missing
 * rather than as the lowest value in the league.
 */
final class Ratio {

    private Ratio() {
    }

    /** Returns numerator over denominator; empty when the denominator is zero. */
    static OptionalDouble of(int numerator, int denominator) {
        return denominator == 0
                ? OptionalDouble.empty()
                : OptionalDouble.of((double) numerator / denominator);
    }
}
