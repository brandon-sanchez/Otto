package otto.ceiling;

import java.util.Optional;

import otto.lineup.LeagueScoring;

/**
 * One player's ceiling and floor: how often he has a boom week and the
 * range his weekly scores usually cover, all read from one
 * {@link WeeklyRange} around his projection. Past boom weeks play no part
 * in it: once the projection is known they add almost nothing, so they
 * are context for the reader, never an input.
 *
 * @param projection the projected points for one game, in league scoring
 * @param boomChance the chance, from 0 to 1, that a week reaches the boom line
 * @param floor the score one week in ten falls below
 * @param typicalLow the score a quarter of weeks fall below
 * @param typicalHigh the score a quarter of weeks rise above
 * @param bigWeek the score one week in ten rises above
 */
public record Ceiling(
        String position,
        double projection,
        BoomLine boomLine,
        double boomChance,
        double floor,
        double typicalLow,
        double typicalHigh,
        double bigWeek) {

    private static final double FLOOR = 0.10;
    private static final double TYPICAL_LOW = 0.25;
    private static final double TYPICAL_HIGH = 0.75;
    private static final double BIG_WEEK = 0.90;

    /**
     * Returns the estimate for a projection, or empty when the position
     * has no boom line or spread, or the projection is not above zero.
     */
    public static Optional<Ceiling> estimate(String position, double projection,
            LeagueScoring scoring) {
        return BoomLine.of(position, scoring).flatMap(line -> WeeklyRange
                .around(position, projection)
                .map(range -> new Ceiling(position, projection, line,
                        range.chanceOfAtLeast(line.points()),
                        range.percentile(FLOOR),
                        range.percentile(TYPICAL_LOW),
                        range.percentile(TYPICAL_HIGH),
                        range.percentile(BIG_WEEK))));
    }
}
