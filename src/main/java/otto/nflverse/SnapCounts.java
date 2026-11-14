package otto.nflverse;

import java.time.Instant;
import java.util.List;

/**
 * The stored, trimmed nflverse snap counts: one row per skill player per
 * regular-season game.
 *
 * @param priorSeasonFinal true when the current season has no played
 *        week yet, so these rows are last season's final record
 */
public record SnapCounts(String season, boolean priorSeasonFinal, Instant assetUpdatedAt, Instant checkedAt,
        Coverage coverage,
        List<SnapLine> rows) implements NflverseFeed {

    /**
     * One player's offensive snaps in one game.
     *
     * @param pfrId his Pro Football Reference id, the only id the file carries
     * @param team his team, in Sleeper's codes
     * @param offenseSnaps the offensive snaps he played
     * @param offensePct his share of his team's offensive snaps, from 0 to 1
     */
    public record SnapLine(String pfrId, String position, String team, int week, int offenseSnaps,
            double offensePct, String gameId) {
    }

    public int newestWeek() {
        return rows.stream().mapToInt(SnapLine::week).max().orElse(0);
    }

}
