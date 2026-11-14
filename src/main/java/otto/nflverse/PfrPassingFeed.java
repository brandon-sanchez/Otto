package otto.nflverse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static otto.nflverse.FeedRows.REGULAR_SEASON;
import static otto.nflverse.FeedRows.blankOrNa;
import static otto.nflverse.FeedRows.requireColumns;

/**
 * PFR's weekly advanced passing file, from nflverse's {@code pfr_advstats}
 * release: one row per player who threw a pass in a game, trick plays
 * included.
 */
final class PfrPassingFeed implements FeedSpec<PfrPassing.PasserLine, PfrPassing> {

    private static final String PRESSURED_PCT = "times_pressured_pct";

    private static final Set<String> COLUMNS = Set.of(
            "game_id", "week", "game_type", "team", "opponent", "pfr_player_id",
            "pfr_player_name", "times_pressured", PRESSURED_PCT);

    @Override
    public FeedId id() {
        return FeedId.PFR_PASSING;
    }

    @Override
    public String documentName() {
        return "nflverse-pfr-passing";
    }

    @Override
    public String tag() {
        return "pfr_advstats";
    }

    @Override
    public String asset(String season) {
        return "advstats_week_pass_%s.csv".formatted(season);
    }

    @Override
    public SeasonRule seasonRule() {
        return SeasonRule.LAST_PLAYED;
    }

    @Override
    public Grain<PfrPassing.PasserLine> grain() {
        return new Grain.PerTeamWeek<>(PfrPassing.PasserLine::week, PfrPassing.PasserLine::team);
    }

    @Override
    public List<PfrPassing.PasserLine> read(Basis basis, Stream<Csv.Row> rows) {
        List<PfrPassing.PasserLine> lines = new ArrayList<>();
        rows.forEach(row -> {
            requireColumns(row, COLUMNS);
            String gameId = row.text("game_id");
            String pfrId = row.text("pfr_player_id");
            if (blankOrNa(gameId) || blankOrNa(pfrId)
                    || !REGULAR_SEASON.equals(row.text("game_type"))) {
                return;
            }
            lines.add(new PfrPassing.PasserLine(gameId, row.integer("week"),
                    NflTeams.normalize(row.text("team")), NflTeams.normalize(row.text("opponent")),
                    pfrId, row.text("pfr_player_name"), row.integer("times_pressured"),
                    pressuredPct(row)));
        });
        return lines;
    }

    /**
     * The published share, or null when the row carries none. A blank or
     * "NA" is not a zero, and neither is a value outside nought to one or
     * a "NaN", which both parse as doubles.
     */
    private static Double pressuredPct(Csv.Row row) {
        String value = row.text(PRESSURED_PCT).trim();
        if (blankOrNa(value)) {
            return null;
        }
        try {
            double share = Double.parseDouble(value);
            return share >= 0.0 && share <= 1.0 ? share : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    @Override
    public PfrPassing document(Basis basis, Instant assetUpdatedAt, Instant checkedAt,
            List<PfrPassing.PasserLine> rows, Coverage coverage) {
        return new PfrPassing(basis.season(), basis.priorSeasonFinal(), assetUpdatedAt, checkedAt,
                coverage, rows);
    }

    @Override
    public PfrPassing recheck(PfrPassing current, Basis basis, Instant checkedAt) {
        return new PfrPassing(basis.season(), basis.priorSeasonFinal(), current.assetUpdatedAt(),
                checkedAt, current.coverage(), current.rows());
    }

    @Override
    public Class<PfrPassing> type() {
        return PfrPassing.class;
    }
}
