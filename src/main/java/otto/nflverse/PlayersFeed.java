package otto.nflverse;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static otto.nflverse.FeedRows.KEPT_POSITIONS;
import static otto.nflverse.FeedRows.blankOrNa;
import static otto.nflverse.FeedRows.optionalNumber;
import static otto.nflverse.FeedRows.requireColumns;

final class PlayersFeed implements FeedSpec<Players.Player, Players> {

    private static final Set<String> COLUMNS = Set.of(
            "gsis_id", "pfr_id", "display_name", "position", "birth_date", "last_season",
            "draft_year", "draft_round", "draft_pick", "draft_team");

    @Override
    public FeedId id() {
        return FeedId.PLAYERS;
    }

    @Override
    public String documentName() {
        return "nflverse-players";
    }

    @Override
    public String tag() {
        return "players";
    }

    @Override
    public String asset(String season) {
        return "players.csv";
    }

    @Override
    public SeasonRule seasonRule() {
        return SeasonRule.CURRENT;
    }

    @Override
    public Grain<Players.Player> grain() {
        return new Grain.Whole<>();
    }

    @Override
    public List<Players.Player> read(Basis basis, Stream<Csv.Row> rows) {
        int oldestSeason = Seasons.previous(basis.season())
                .map(Integer::parseInt)
                .orElseThrow(() -> new IllegalStateException(
                        "season \"%s\" is not a year".formatted(basis.season())));
        List<Players.Player> players = new ArrayList<>();
        rows.forEach(row -> {
            requireColumns(row, COLUMNS);
            String gsisId = row.text("gsis_id");
            Double lastSeason = optionalNumber(row, "last_season");
            if (blankOrNa(gsisId) || !KEPT_POSITIONS.contains(row.text("position"))
                    || lastSeason == null || lastSeason < oldestSeason) {
                return;
            }
            String pfrId = row.text("pfr_id");
            players.add(new Players.Player(gsisId, blankOrNa(pfrId) ? null : pfrId,
                    row.text("display_name"), row.text("position"),
                    birthDate(row.text("birth_date")), draft(row)));
        });
        return players;
    }

    private static LocalDate birthDate(String value) {
        if (blankOrNa(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException unreadable) {
            return null;
        }
    }

    private static Players.Draft draft(Csv.Row row) {
        Double year = optionalNumber(row, "draft_year");
        Double round = optionalNumber(row, "draft_round");
        Double pick = optionalNumber(row, "draft_pick");
        String team = row.text("draft_team");
        if (year == null || round == null || pick == null || blankOrNa(team)) {
            return null;
        }
        return new Players.Draft(year.intValue(), round.intValue(), pick.intValue(),
                NflTeams.normalize(team));
    }

    @Override
    public Players document(Basis basis, Instant assetUpdatedAt, Instant checkedAt,
            List<Players.Player> rows, Coverage coverage) {
        return new Players(basis.season(), assetUpdatedAt, checkedAt, coverage, rows);
    }

    @Override
    public Players recheck(Players current, Basis basis, Instant checkedAt) {
        return new Players(basis.season(), current.assetUpdatedAt(), checkedAt,
                current.coverage(), current.rows());
    }

    @Override
    public Class<Players> type() {
        return Players.class;
    }
}
