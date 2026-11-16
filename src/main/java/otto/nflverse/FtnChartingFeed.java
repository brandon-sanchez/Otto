package otto.nflverse;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static otto.nflverse.FeedRows.blankOrNa;
import static otto.nflverse.FeedRows.requireColumns;

final class FtnChartingFeed implements FeedSpec<FtnCharting.Play, FtnCharting> {

    private static final Set<String> COLUMNS = Set.of(
            "nflverse_game_id", "week", "nflverse_play_id", "date_pulled", "read_thrown");

    private static final String NO_THROW = "0";

    @Override
    public FeedId id() {
        return FeedId.FTN_CHARTING;
    }

    @Override
    public String documentName() {
        return "nflverse-ftn-charting";
    }

    @Override
    public String tag() {
        return "ftn_charting";
    }

    @Override
    public String asset(String season) {
        return "ftn_charting_%s.csv".formatted(season);
    }

    @Override
    public SeasonRule seasonRule() {
        return SeasonRule.CURRENT;
    }

    @Override
    public Grain<FtnCharting.Play> grain() {
        return new Grain.PerGame<>(FtnCharting.Play::week, FtnCharting.Play::gameId);
    }

    @Override
    public List<FtnCharting.Play> read(Basis basis, Stream<Csv.Row> rows) {
        List<FtnCharting.Play> plays = new ArrayList<>();
        rows.forEach(row -> {
            requireColumns(row, COLUMNS);
            String gameId = row.text("nflverse_game_id");
            if (blankOrNa(gameId)) {
                return;
            }
            plays.add(new FtnCharting.Play(gameId, row.integer("week"),
                    row.text("nflverse_play_id"), datePulled(row.text("date_pulled")),
                    readThrown(row.text("read_thrown"))));
        });
        return plays;
    }

    private static String readThrown(String value) {
        String code = value.trim();
        return blankOrNa(code) ? null : code;
    }

    private static Instant datePulled(String value) {
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException unreadable) {
            throw new IllegalStateException(
                    "schema drift: date_pulled \"%s\" is not an instant".formatted(value));
        }
    }

    @Override
    public Optional<Instant> stamp(FtnCharting.Play play) {
        return Optional.of(play.datePulled());
    }

    /**
     * A game's content is which plays were charted and the read each
     * throw went to. A bulk re-pull moves every stamp and changes
     * nothing here.
     */
    @Override
    public Object content(FtnCharting.Play play) {
        return new ChartedPlay(play.playId(), play.readThrown());
    }

    private record ChartedPlay(String playId, String readThrown) {
    }

    @Override
    public int contentVersion() {
        return 2;
    }

    @Override
    public FtnCharting document(Basis basis, Instant assetUpdatedAt, Instant checkedAt,
            List<FtnCharting.Play> rows, Coverage coverage) {
        List<FtnCharting.Read> reads = rows.stream()
                .filter(play -> play.readThrown() != null && !NO_THROW.equals(play.readThrown()))
                .map(play -> new FtnCharting.Read(play.gameId(), play.playId(), play.readThrown()))
                .toList();
        return new FtnCharting(basis.season(), assetUpdatedAt, checkedAt, coverage, reads);
    }

    @Override
    public FtnCharting recheck(FtnCharting current, Basis basis, Instant checkedAt) {
        return new FtnCharting(basis.season(), current.assetUpdatedAt(), checkedAt,
                current.coverage(), current.reads());
    }

    @Override
    public Class<FtnCharting> type() {
        return FtnCharting.class;
    }
}
