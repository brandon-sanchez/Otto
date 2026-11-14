package otto.nflverse;

import java.time.Instant;
import java.util.List;

/**
 * ffopportunity's expected fantasy points: what each skill player's
 * targets, carries and throws were worth on average, set beside what he
 * actually scored. The points are ffopportunity's own full-PPR scoring,
 * not any league's, so they compare players with each other and never
 * stand in for a league's points.
 *
 * @param priorSeasonFinal true when the current season has no played
 *        week yet, so these rows are last season's final record
 */
public record ExpectedPoints(
        String season,
        boolean priorSeasonFinal,
        Instant assetUpdatedAt,
        Instant checkedAt,
        Coverage coverage,
        List<Line> rows) implements NflverseFeed {

    /**
     * One player's regular-season game. Every number is null when the
     * file left it blank.
     *
     * @param team his offence, in Sleeper's codes
     * @param touchdowns the passing, rushing and receiving touchdowns he scored
     * @param expectedTouchdowns the touchdowns his opportunities were worth on average
     * @param fantasyPoints what he scored under ffopportunity's scoring
     * @param expectedFantasyPoints what his opportunities were worth on
     *        average under the same scoring
     */
    public record Line(String gsisId, String position, String team, int week, String gameId,
            Double touchdowns, Double expectedTouchdowns, Double fantasyPoints,
            Double expectedFantasyPoints) {
    }
}
