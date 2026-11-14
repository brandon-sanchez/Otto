package otto.nflverse;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static otto.nflverse.FeedRows.REGULAR_SEASON;
import static otto.nflverse.FeedRows.blankOrNa;
import static otto.nflverse.FeedRows.requireColumns;

/**
 * PFR's weekly advanced defense file, from nflverse's {@code pfr_advstats}
 * release. It has one row per defender per game.
 */
final class PfrDefenseFeed implements FeedSpec<PfrDefense.GamePressures, PfrDefense> {

    private static final Set<String> COLUMNS = Set.of(
            "game_id", "week", "game_type", "team", "opponent", "def_pressures");

    @Override
    public FeedId id() {
        return FeedId.PFR_DEFENSE;
    }

    @Override
    public String documentName() {
        return "nflverse-pfr-defense";
    }

    @Override
    public String tag() {
        return "pfr_advstats";
    }

    @Override
    public String asset(String season) {
        return "advstats_week_def_%s.csv".formatted(season);
    }

    @Override
    public SeasonRule seasonRule() {
        return SeasonRule.LAST_PLAYED;
    }

    @Override
    public Grain<PfrDefense.GamePressures> grain() {
        return new Grain.PerTeamWeek<>(PfrDefense.GamePressures::week,
                PfrDefense.GamePressures::team);
    }

    @Override
    public List<PfrDefense.GamePressures> read(Basis basis, Stream<Csv.Row> rows) {
        Map<List<String>, PfrDefense.GamePressures> byGame = new LinkedHashMap<>();
        rows.forEach(row -> {
            requireColumns(row, COLUMNS);
            String gameId = row.text("game_id");
            if (blankOrNa(gameId) || !REGULAR_SEASON.equals(row.text("game_type"))) {
                return;
            }
            String team = NflTeams.normalize(row.text("team"));
            PfrDefense.GamePressures player = new PfrDefense.GamePressures(gameId,
                    row.integer("week"), team, NflTeams.normalize(row.text("opponent")),
                    row.integer("def_pressures"));
            byGame.merge(List.of(gameId, team), player, (sum, more) ->
                    new PfrDefense.GamePressures(sum.gameId(), sum.week(), sum.team(),
                            sum.opponent(), sum.pressures() + more.pressures()));
        });
        return List.copyOf(byGame.values());
    }

    @Override
    public PfrDefense document(Basis basis, Instant assetUpdatedAt, Instant checkedAt,
            List<PfrDefense.GamePressures> rows, Coverage coverage) {
        return new PfrDefense(basis.season(), basis.priorSeasonFinal(), assetUpdatedAt, checkedAt,
                coverage, rows);
    }

    @Override
    public PfrDefense recheck(PfrDefense current, Basis basis, Instant checkedAt) {
        return new PfrDefense(basis.season(), basis.priorSeasonFinal(), current.assetUpdatedAt(),
                checkedAt, current.coverage(), current.rows());
    }

    @Override
    public Class<PfrDefense> type() {
        return PfrDefense.class;
    }
}
