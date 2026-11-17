package otto.buysell;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A player's expected points per game, blending last season with this
 * one. Last season counts fully before his first game and fades as this
 * season's games arrive, fastest for running backs, whose roles settle
 * soonest, and slowest for tight ends. This season's half is the average
 * of his points and his xFP per game, because xFP settles sooner but
 * points carry a little real skill. Last season's points are first
 * pulled a tenth of the way toward the position's average, since even
 * last year's best players fall back about that much on average.
 *
 * @param perGame the expected points per game, in ffopportunity's
 *        full-PPR scoring
 * @param lastSeasonWeight the share of {@code perGame} that comes from
 *        last season, from 0 to 1
 * @param resets what changed since last season to drop its weight to
 *        0, empty when nothing did
 */
public record Baseline(double perGame, double lastSeasonWeight,
        List<TeamContext.Change> resets) {

    /** Last season's weight at 0, 2, 4, 6 and 8 or more games played this season. */
    private static final Map<String, double[]> WEIGHTS = Map.of(
            "RB", new double[] {1.0, 0.5, 0.35, 0.25, 0.2},
            "WR", new double[] {1.0, 0.55, 0.45, 0.35, 0.3},
            "TE", new double[] {1.0, 0.65, 0.55, 0.4, 0.35});

    private static final double PULL_TO_AVERAGE = 0.1;

    public Baseline {
        resets = List.copyOf(resets);
    }

    /**
     * Blends the two seasons.
     *
     * @param lastSeason his full last season, empty when he played fewer
     *        than eight games, which gives it no weight
     * @param positionAverage last season's average points per game at
     *        his position, empty when unknown, which leaves his own
     *        points unpulled
     * @param changes what changed around him since last season; any
     *        change drops last season's weight to 0 once he has played
     *        a game this season
     */
    static Baseline of(PlayerSeason thisSeason, Optional<PlayerSeason> lastSeason,
            Optional<Double> positionAverage, List<TeamContext.Change> changes) {
        double thisSeasonLevel = (thisSeason.pointsPerGame() + thisSeason.expectedPerGame()) / 2;
        if (lastSeason.isEmpty() || (!changes.isEmpty() && thisSeason.gamesPlayed() > 0)) {
            return new Baseline(thisSeasonLevel, 0, changes);
        }
        double before = lastSeason.get().pointsPerGame();
        double pulled = positionAverage
                .map(average -> before + PULL_TO_AVERAGE * (average - before))
                .orElse(before);
        double weight = weight(thisSeason.position(), thisSeason.gamesPlayed());
        return new Baseline(weight * pulled + (1 - weight) * thisSeasonLevel, weight, List.of());
    }

    /** Interpolates the weight table between its even game counts. */
    static double weight(String position, int games) {
        double[] table = WEIGHTS.get(position);
        int step = Math.min(games / 2, table.length - 1);
        if (step == table.length - 1) {
            return table[step];
        }
        double between = (games % 2) / 2.0;
        return table[step] + between * (table[step + 1] - table[step]);
    }
}
