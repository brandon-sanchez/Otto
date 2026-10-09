package otto.nflverse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static otto.nflverse.FeedRows.KEPT_POSITIONS;
import static otto.nflverse.FeedRows.REGULAR_SEASON;
import static otto.nflverse.FeedRows.blankOrNa;
import static otto.nflverse.FeedRows.requireColumns;

final class SnapCountsFeed implements FeedSpec<SnapCounts.SnapLine, SnapCounts> {

    private static final Set<String> COLUMNS = Set.of(
            "pfr_player_id", "week", "game_type", "position", "offense_pct");

    @Override
    public FeedId id() {
        return FeedId.SNAP_COUNTS;
    }

    @Override
    public String documentName() {
        return "nflverse-snap-counts";
    }

    @Override
    public String tag() {
        return "snap_counts";
    }

    @Override
    public String asset(String season) {
        return "snap_counts_%s.csv".formatted(season);
    }

    @Override
    public SeasonRule seasonRule() {
        return SeasonRule.LAST_PLAYED;
    }

    @Override
    public List<SnapCounts.SnapLine> read(Basis basis, Stream<Csv.Row> rows) {
        List<SnapCounts.SnapLine> lines = new ArrayList<>();
        rows.forEach(row -> {
            requireColumns(row, COLUMNS);
            String pfrId = row.text("pfr_player_id");
            if (blankOrNa(pfrId) || !REGULAR_SEASON.equals(row.text("game_type"))
                    || !KEPT_POSITIONS.contains(row.text("position"))) {
                return;
            }
            try {
                double share = Double.parseDouble(row.text("offense_pct"));
                if (share >= 0.0 && share <= 1.0) {
                    lines.add(new SnapCounts.SnapLine(pfrId, row.text("position"),
                            row.integer("week"), share));
                }
            } catch (NumberFormatException ignored) {
            }
        });
        return lines;
    }

    @Override
    public SnapCounts document(Basis basis, Instant assetUpdatedAt, Instant checkedAt,
            List<SnapCounts.SnapLine> rows) {
        return new SnapCounts(basis.season(), basis.priorSeasonFinal(), assetUpdatedAt, checkedAt,
                rows);
    }

    @Override
    public Class<SnapCounts> type() {
        return SnapCounts.class;
    }
}
