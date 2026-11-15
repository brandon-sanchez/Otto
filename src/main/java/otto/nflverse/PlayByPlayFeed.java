package otto.nflverse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static otto.nflverse.FeedRows.REGULAR_SEASON;
import static otto.nflverse.FeedRows.blankOrNa;
import static otto.nflverse.FeedRows.optionalNumber;
import static otto.nflverse.FeedRows.requireColumns;

final class PlayByPlayFeed implements FeedSpec<PlayByPlay.Play, PlayByPlay> {

    /**
     * Scrambles are runs and count as the quarterback's carry, as they do
     * in the weekly stats, and as his dropback. nflverse writes a sack as
     * a pass. Every other play type carries no opportunity.
     */
    private static final Set<String> PLAYS = Set.of("pass", "run");

    private static final Set<String> COLUMNS = Set.of(
            "game_id", "play_id", "week", "season_type", "posteam", "defteam", "play_type",
            "yardline_100", "air_yards", "rush_attempt", "two_point_attempt",
            "receiver_player_id", "rusher_player_id", "passer_player_id", "qb_scramble");

    private static final String PASS = "pass";

    @Override
    public FeedId id() {
        return FeedId.PLAY_BY_PLAY;
    }

    @Override
    public String documentName() {
        return "nflverse-play-by-play";
    }

    @Override
    public String tag() {
        return "pbp";
    }

    @Override
    public String asset(String season) {
        return "play_by_play_%s.csv".formatted(season);
    }

    @Override
    public SeasonRule seasonRule() {
        return SeasonRule.LAST_PLAYED;
    }

    @Override
    public Grain<PlayByPlay.Play> grain() {
        return new Grain.PerGame<>(PlayByPlay.Play::week, PlayByPlay.Play::gameId);
    }

    @Override
    public List<PlayByPlay.Play> read(Basis basis, Stream<Csv.Row> rows) {
        List<PlayByPlay.Play> kept = new ArrayList<>();
        rows.forEach(row -> {
            requireColumns(row, COLUMNS);
            Double yardline = optionalNumber(row, "yardline_100");
            if (!REGULAR_SEASON.equals(row.text("season_type"))
                    || !PLAYS.contains(row.text("play_type"))
                    || row.integer("two_point_attempt") == 1
                    || yardline == null) {
                return;
            }
            Double scrambleFlag = optionalNumber(row, "qb_scramble");
            boolean scramble = scrambleFlag != null && scrambleFlag == 1;
            if (PASS.equals(row.text("play_type")) || scramble) {
                String passer = row.text(scramble ? "rusher_player_id" : "passer_player_id");
                kept.add(new PlayByPlay.Dropback(row.text("game_id"), row.text("play_id"),
                        row.integer("week"), NflTeams.normalize(row.text("posteam")),
                        NflTeams.normalize(row.text("defteam")), blankOrNa(passer) ? null : passer,
                        scramble));
            }
            String receiver = row.text("receiver_player_id");
            if (!blankOrNa(receiver)) {
                Double airYards = optionalNumber(row, "air_yards");
                kept.add(opportunity(row, PlayByPlay.Kind.TARGET, receiver, yardline,
                        airYards == null ? null : airYards.intValue()));
            }
            String rusher = row.text("rusher_player_id");
            if (!blankOrNa(rusher) && row.integer("rush_attempt") == 1) {
                kept.add(opportunity(row, PlayByPlay.Kind.CARRY, rusher, yardline, null));
            }
        });
        return kept;
    }

    private static PlayByPlay.Opportunity opportunity(Csv.Row row, PlayByPlay.Kind kind,
            String gsisId, double yardline, Integer airYards) {
        return new PlayByPlay.Opportunity(row.text("game_id"), row.text("play_id"),
                row.integer("week"), NflTeams.normalize(row.text("posteam")),
                NflTeams.normalize(row.text("defteam")), kind, gsisId, (int) yardline, airYards);
    }

    @Override
    public int contentVersion() {
        return 4;
    }

    @Override
    public PlayByPlay document(Basis basis, Instant assetUpdatedAt, Instant checkedAt,
            List<PlayByPlay.Play> rows, Coverage coverage) {
        List<PlayByPlay.Opportunity> opportunities = new ArrayList<>();
        List<PlayByPlay.Dropback> dropbacks = new ArrayList<>();
        for (PlayByPlay.Play play : rows) {
            switch (play) {
                case PlayByPlay.Opportunity opportunity -> opportunities.add(opportunity);
                case PlayByPlay.Dropback dropback -> dropbacks.add(dropback);
            }
        }
        return new PlayByPlay(basis.season(), basis.priorSeasonFinal(), assetUpdatedAt, checkedAt,
                coverage, opportunities, dropbacks);
    }

    @Override
    public PlayByPlay recheck(PlayByPlay current, Basis basis, Instant checkedAt) {
        return new PlayByPlay(basis.season(), basis.priorSeasonFinal(), current.assetUpdatedAt(),
                checkedAt, current.coverage(), current.rows(), current.dropbacks());
    }

    @Override
    public Class<PlayByPlay> type() {
        return PlayByPlay.class;
    }
}
