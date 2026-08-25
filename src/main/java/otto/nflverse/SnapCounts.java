package otto.nflverse;

import java.time.Instant;
import java.util.List;

public record SnapCounts(String season, boolean priorSeasonFinal, Instant assetUpdatedAt, Instant checkedAt,
        List<SnapLine> rows) implements NflverseFeed {

    public record SnapLine(String pfrId, String position, int week, double offensePct) {
    }

    public SnapCounts withCheckedAt(Instant newCheckedAt) {
        return new SnapCounts(season, priorSeasonFinal, assetUpdatedAt, newCheckedAt, rows);
    }

    public int newestWeek() {
        return rows.stream().mapToInt(SnapLine::week).max().orElse(0);
    }

}
