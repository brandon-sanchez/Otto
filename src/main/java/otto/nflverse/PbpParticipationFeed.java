package otto.nflverse;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static otto.nflverse.FeedRows.KEPT_POSITIONS;
import static otto.nflverse.FeedRows.blankOrNa;
import static otto.nflverse.FeedRows.requireColumns;

/**
 * Last season's {@code pbp_participation} file, folded per game into each
 * offense's dropbacks and stored as season totals per defense and per
 * player.
 */
final class PbpParticipationFeed implements FeedSpec<PbpParticipation.TeamGame, PbpParticipation> {

    private static final Set<String> COLUMNS = Set.of(
            "nflverse_game_id", "possession_team", "offense_players", "offense_positions",
            "was_pressure", "defense_man_zone_type", "defense_coverage_type");

    /** The file carries the playoffs too, with no season-type column; their weeks follow 18. */
    private static final int LAST_REGULAR_SEASON_WEEK = 18;

    private static final Pattern GAME_ID = Pattern.compile("\\d{4}_(\\d{2})_([A-Z]+)_([A-Z]+)");
    private static final String MAN = "MAN_COVERAGE";
    private static final String ZONE = "ZONE_COVERAGE";

    @Override
    public FeedId id() {
        return FeedId.PBP_PARTICIPATION;
    }

    @Override
    public String documentName() {
        return "nflverse-pbp-participation";
    }

    @Override
    public String tag() {
        return "pbp_participation";
    }

    @Override
    public String asset(String season) {
        return "pbp_participation_%s.csv".formatted(season);
    }

    @Override
    public SeasonRule seasonRule() {
        return SeasonRule.PREVIOUS;
    }

    @Override
    public Grain<PbpParticipation.TeamGame> grain() {
        return new Grain.PerGame<>(PbpParticipation.TeamGame::week,
                PbpParticipation.TeamGame::gameId);
    }

    @Override
    public List<PbpParticipation.TeamGame> read(Basis basis, Stream<Csv.Row> rows) {
        Map<List<String>, TeamGameTally> byTeamGame = new LinkedHashMap<>();
        rows.forEach(row -> {
            requireColumns(row, COLUMNS);
            String manZone = row.text("defense_man_zone_type");
            if (blankOrNa(manZone)) {
                return;
            }
            String gameId = row.text("nflverse_game_id");
            Matcher game = GAME_ID.matcher(gameId);
            if (!game.matches()) {
                throw new IllegalStateException(
                        "schema drift: nflverse_game_id \"%s\" is not season_week_away_home"
                                .formatted(gameId));
            }
            int week = Integer.parseInt(game.group(1));
            if (week > LAST_REGULAR_SEASON_WEEK) {
                return;
            }
            String offense = row.text("possession_team");
            String defense = opponent(gameId, offense, game.group(2), game.group(3));
            byTeamGame.computeIfAbsent(List.of(gameId, offense),
                            key -> new TeamGameTally(gameId, week, NflTeams.normalize(offense),
                                    NflTeams.normalize(defense)))
                    .add(row, manZone);
        });
        return byTeamGame.values().stream().map(TeamGameTally::teamGame).toList();
    }

    private static String opponent(String gameId, String offense, String away, String home) {
        if (offense.equals(away)) {
            return home;
        }
        if (offense.equals(home)) {
            return away;
        }
        throw new IllegalStateException("schema drift: possession_team \"%s\" plays neither side of %s"
                .formatted(offense, gameId));
    }

    @Override
    public PbpParticipation document(Basis basis, Instant assetUpdatedAt, Instant checkedAt,
            List<PbpParticipation.TeamGame> rows, Coverage coverage) {
        return new PbpParticipation(basis.season(), assetUpdatedAt, checkedAt, coverage,
                defenses(rows), players(rows));
    }

    @Override
    public PbpParticipation recheck(PbpParticipation current, Basis basis, Instant checkedAt) {
        return new PbpParticipation(basis.season(), current.assetUpdatedAt(), checkedAt,
                current.coverage(), current.defenses(), current.players());
    }

    @Override
    public Class<PbpParticipation> type() {
        return PbpParticipation.class;
    }

    private static List<PbpParticipation.DefenseTendencies> defenses(
            List<PbpParticipation.TeamGame> rows) {
        Map<String, PbpParticipation.DefenseTendencies> byDefense = new TreeMap<>();
        rows.forEach(game -> byDefense.merge(game.defense(),
                new PbpParticipation.DefenseTendencies(game.defense(), game.dropbacks(),
                        game.man(), game.zone(), game.shells(), game.pressures()),
                PbpParticipationFeed::plus));
        return List.copyOf(byDefense.values());
    }

    private static PbpParticipation.DefenseTendencies plus(PbpParticipation.DefenseTendencies a,
            PbpParticipation.DefenseTendencies b) {
        Map<String, Integer> shells = new HashMap<>(a.shells());
        b.shells().forEach((shell, count) -> shells.merge(shell, count, Integer::sum));
        return new PbpParticipation.DefenseTendencies(a.team(), a.dropbacks() + b.dropbacks(),
                a.man() + b.man(), a.zone() + b.zone(), shells, a.pressures() + b.pressures());
    }

    private static List<PbpParticipation.DropbackShare> players(
            List<PbpParticipation.TeamGame> rows) {
        Map<List<String>, PbpParticipation.DropbackShare> byPlayer = new HashMap<>();
        rows.forEach(game -> game.onField().forEach((gsisId, dropbacks) -> byPlayer.merge(
                List.of(gsisId, game.offense()),
                new PbpParticipation.DropbackShare(gsisId, game.offense(), 1, dropbacks,
                        game.dropbacks()),
                (a, b) -> new PbpParticipation.DropbackShare(a.gsisId(), a.team(),
                        a.games() + b.games(), a.onFieldDropbacks() + b.onFieldDropbacks(),
                        a.teamDropbacks() + b.teamDropbacks()))));
        return byPlayer.values().stream()
                .sorted(Comparator.comparing(PbpParticipation.DropbackShare::gsisId)
                        .thenComparing(PbpParticipation.DropbackShare::team))
                .toList();
    }

    private static final class TeamGameTally {

        private final String gameId;
        private final int week;
        private final String offense;
        private final String defense;
        private int dropbacks;
        private int man;
        private int zone;
        private int pressures;
        private final Map<String, Integer> shells = new HashMap<>();
        private final Map<String, Integer> onField = new HashMap<>();

        TeamGameTally(String gameId, int week, String offense, String defense) {
            this.gameId = gameId;
            this.week = week;
            this.offense = offense;
            this.defense = defense;
        }

        void add(Csv.Row row, String manZone) {
            dropbacks++;
            if (MAN.equals(manZone)) {
                man++;
            } else if (ZONE.equals(manZone)) {
                zone++;
            }
            String shell = row.text("defense_coverage_type");
            if (!blankOrNa(shell)) {
                shells.merge(shell, 1, Integer::sum);
            }
            if ("TRUE".equals(row.text("was_pressure"))) {
                pressures++;
            }
            String[] players = row.text("offense_players").split(";");
            String[] positions = row.text("offense_positions").split(";");
            if (players.length != positions.length) {
                throw new IllegalStateException(
                        "schema drift: %d offense_players against %d offense_positions in %s"
                                .formatted(players.length, positions.length, gameId));
            }
            for (int i = 0; i < players.length; i++) {
                if (KEPT_POSITIONS.contains(positions[i]) && !blankOrNa(players[i])) {
                    onField.merge(players[i], 1, Integer::sum);
                }
            }
        }

        PbpParticipation.TeamGame teamGame() {
            return new PbpParticipation.TeamGame(gameId, week, offense, defense, dropbacks, man,
                    zone, shells, pressures, onField);
        }
    }
}
