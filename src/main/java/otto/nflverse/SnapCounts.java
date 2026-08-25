package otto.nflverse;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** Published offensive snap share for one season. */
public record SnapCounts(String season, boolean priorSeasonFinal, Instant assetUpdatedAt, Instant checkedAt,
        List<SnapLine> rows) implements NflverseFeed {

    public record SnapLine(String pfrId, int week, double offensePct) {
    }

    public SnapCounts withCheckedAt(Instant newCheckedAt) {
        return new SnapCounts(season, priorSeasonFinal, assetUpdatedAt, newCheckedAt, rows);
    }

    public int newestWeek() {
        return rows.stream().mapToInt(SnapLine::week).max().orElse(0);
    }

    public Optional<Double> share(String pfrId, int week) {
        return rows.stream()
                .filter(row -> row.pfrId().equals(pfrId) && row.week() == week)
                .map(SnapLine::offensePct)
                .findFirst();
    }

    public Map<String, List<SnapLine>> byPlayer() {
        return rows.stream().collect(Collectors.groupingBy(SnapLine::pfrId));
    }
}
