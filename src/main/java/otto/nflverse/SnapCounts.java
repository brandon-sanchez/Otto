package otto.nflverse;

import java.time.Instant;
import java.util.List;

public record SnapCounts(String season, boolean priorSeasonFinal, Instant assetUpdatedAt, Instant checkedAt,
        Coverage coverage,
        List<SnapLine> rows) implements NflverseFeed<SnapCounts.SnapLine> {

    public record SnapLine(String pfrId, String position, int week, double offensePct,
            String gameId) {
    }

    public int newestWeek() {
        return rows.stream().mapToInt(SnapLine::week).max().orElse(0);
    }

}
