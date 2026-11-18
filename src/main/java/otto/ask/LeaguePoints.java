package otto.ask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.advice.Ordinal;
import otto.directory.DirectoryPlayer;
import otto.directory.PlayerDirectory;
import otto.directory.PlayerDirectoryStore;
import otto.lineup.LeagueScoring;
import otto.sleeper.PlayedWeekStats;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;

/**
 * Reads what players actually scored in one league's own scoring over
 * played weeks, and where one of them ranks at his position on it.
 *
 * <p>Sleeper's own score for the league, the {@code players_points} on
 * its matchups, wins whenever it is there: it is the number the league
 * itself counted. Every other week prices the player's Sleeper stat line
 * through {@link LeagueScoring}, and a week neither source holds stays
 * unknown rather than zero.
 */
@Component
class LeaguePoints {

    private static final Logger log = LoggerFactory.getLogger(LeaguePoints.class);

    /** How far Sleeper's score may sit from the priced line before it is logged. */
    private static final double CROSS_CHECK_TOLERANCE = 0.05;

    private final SleeperAdapter sleeper;
    private final PlayerDirectoryStore directory;

    LeaguePoints(SleeperAdapter sleeper, PlayerDirectoryStore directory,
            OttoProperties properties) {
        this.sleeper = sleeper.cachedWithin(properties.sleeperCadence());
        this.directory = directory;
    }

    /** One week of one player's points in league scoring. */
    sealed interface Game permits Scored, DidNotPlay, Unknown {

        int week();
    }

    /**
     * @param official true when the points are Sleeper's own score for the
     *        league, false when they price his stat line
     */
    record Scored(int week, double points, boolean official) implements Game {
    }

    /** Sleeper holds his team's game and no line shows him playing in it. */
    record DidNotPlay(int week) implements Game {
    }

    /** @param reason why no source holds the week, in words fit to show the user */
    record Unknown(int week, String reason) implements Game {
    }

    /**
     * Where a player's points rank at his position over the same weeks.
     *
     * @param rank 1 for the most points; players who tie share a rank
     * @param of how many players at the position scored in those weeks
     * @param teams how many teams the league has, empty when Sleeper could
     *        not serve its rosters
     */
    record Rank(int rank, int of, OptionalInt teams) {
    }

    /**
     * @param games one entry per week asked about, oldest first
     * @param rank empty when he scored in none of the weeks or the Player
     *        Directory is not stored
     */
    record Report(List<Game> games, Optional<Rank> rank) {

        Report {
            games = List.copyOf(games);
        }

        List<Scored> scored() {
            return only(Scored.class);
        }

        List<Unknown> unknown() {
            return only(Unknown.class);
        }

        List<Integer> satOut() {
            return only(DidNotPlay.class).stream().map(DidNotPlay::week).toList();
        }

        double total() {
            return scored().stream().mapToDouble(Scored::points).sum();
        }

        private <T extends Game> List<T> only(Class<T> kind) {
            return games.stream().filter(kind::isInstance).map(kind::cast).toList();
        }
    }

    /**
     * What one call reads.
     *
     * @param season the season the weeks belong to
     * @param matchupsHoldSeason true when the league bound to this work
     *        played that season, so its matchups hold Sleeper's own scores
     * @param team his team in Sleeper's codes, which tells a week Sleeper
     *        has not published from one he sat out
     * @param weeks the weeks his team played, oldest first
     */
    record Request(LeagueScoring scoring, String season, boolean matchupsHoldSeason,
            DirectoryPlayer player, String team, List<Integer> weeks) {

        Request {
            if (weeks.isEmpty()) {
                throw new IllegalArgumentException("a points request needs at least one week");
            }
            weeks = List.copyOf(weeks);
        }
    }

    /**
     * Reads one player's points over his team's weeks and his rank at his
     * position over the same weeks.
     *
     * <p>Side effects: Sleeper reads of each week's stats and the league's
     * rosters, plus the league's matchups for each week when the request's
     * {@code matchupsHoldSeason} is true; each is answered from the cache
     * while younger than the cadence. Reads the stored Player Directory.
     * Logs a week where Sleeper's score and his priced line disagree.
     */
    Report read(Request request) {
        List<Week> weeks = request.weeks().stream()
                .map(week -> load(request.season(), week, request.matchupsHoldSeason()))
                .toList();
        List<Game> games = weeks.stream()
                .map(week -> game(week, request.scoring(), request.player(), request.team()))
                .toList();
        Optional<Rank> rank = games.stream().anyMatch(Scored.class::isInstance)
                ? directory.read().flatMap(stored -> rank(stored, weeks, request))
                : Optional.empty();
        return new Report(games, rank);
    }

    /**
     * One week's sources.
     *
     * @param official Sleeper's own score for every player rostered in the
     *        league that week, by player id
     */
    private record Week(int number, SourceResult<PlayedWeekStats> stats,
            Map<String, Double> official) {
    }

    /** What his stat line alone says about one week. */
    private sealed interface Line {

        /** @param priced his line in league scoring; 0 when it holds nothing scored */
        record Played(double priced) implements Line {
        }

        record Absent() implements Line {
        }

        record NotPublished(String reason) implements Line {
        }
    }

    private Week load(String season, int week, boolean matchupsHoldSeason) {
        Map<String, Double> official = new HashMap<>();
        if (matchupsHoldSeason) {
            switch (sleeper.matchups(week)) {
                case SourceResult.Ok<List<SleeperAdapter.StarterLine>> ok ->
                    ok.value().forEach(line -> official.putAll(line.playerPoints()));
                case SourceResult.Unavailable<List<SleeperAdapter.StarterLine>> unavailable ->
                    log.info("No league scores for week {}: {}", week, unavailable.reason());
            }
        }
        return new Week(week, sleeper.playedWeek(season, week), official);
    }

    private static Line line(Week week, LeagueScoring scoring, DirectoryPlayer player,
            String team) {
        return switch (week.stats()) {
            case SourceResult.Unavailable<PlayedWeekStats> unavailable -> new Line.NotPublished(
                    "Sleeper's week %d stats could not be read".formatted(week.number()));
            case SourceResult.Ok<PlayedWeekStats> ok -> ok.value().played(player.playerId())
                    .<Line>map(played -> new Line.Played(
                            scoring.price(played, player.position()).orElse(0.0)))
                    .orElseGet(() -> team != null && !ok.value().holdsGame(team)
                            ? new Line.NotPublished("Sleeper has not published %s's week %d "
                                    .formatted(team, week.number()) + "game yet")
                            : new Line.Absent());
        };
    }

    /**
     * Lays Sleeper's own score over what the line says. Sleeper scores
     * every rostered player, bench included, so its 0 for a week no line
     * shows him playing is a week he sat out, not a 0-point game.
     */
    private static Game game(Week week, LeagueScoring scoring, DirectoryPlayer player,
            String team) {
        Line line = line(week, scoring, player, team);
        Optional<Double> official = Optional.ofNullable(week.official().get(player.playerId()))
                .filter(points -> line instanceof Line.Played || points != 0.0);
        if (official.isPresent()) {
            if (line instanceof Line.Played played
                    && Math.abs(official.get() - played.priced()) > CROSS_CHECK_TOLERANCE) {
                log.info("Week {} points for player {}: Sleeper scored {}, his line prices to {}",
                        week.number(), player.playerId(), official.get(), played.priced());
            }
            return new Scored(week.number(), official.get(), true);
        }
        return switch (line) {
            case Line.Played played -> new Scored(week.number(), played.priced(), false);
            case Line.Absent absent -> new DidNotPlay(week.number());
            case Line.NotPublished notPublished -> new Unknown(week.number(),
                    notPublished.reason());
        };
    }

    /** Returns his points for the week, empty unless a source shows him scoring. */
    private static OptionalDouble points(Week week, LeagueScoring scoring,
            DirectoryPlayer player) {
        Double official = week.official().get(player.playerId());
        if (official != null && official != 0.0) {
            return OptionalDouble.of(official);
        }
        return line(week, scoring, player, null) instanceof Line.Played played
                ? OptionalDouble.of(official == null ? played.priced() : official)
                : OptionalDouble.empty();
    }

    private Optional<Rank> rank(PlayerDirectory stored, List<Week> weeks, Request request) {
        DirectoryPlayer subject = request.player();
        Map<String, Double> totals = new HashMap<>();
        stored.players().values().stream()
                .filter(player -> subject.position().equals(player.position()))
                .forEach(player -> {
                    List<Double> scored = weeks.stream()
                            .flatMap(week -> Stream.of(points(week, request.scoring(), player))
                                    .filter(OptionalDouble::isPresent)
                                    .map(OptionalDouble::getAsDouble))
                            .toList();
                    if (!scored.isEmpty()) {
                        totals.put(player.playerId(),
                                scored.stream().mapToDouble(Double::doubleValue).sum());
                    }
                });
        Double own = totals.get(subject.playerId());
        if (own == null) {
            return Optional.empty();
        }
        return Optional.of(new Rank(Ordinal.rankAmong(own, totals.values()), totals.size(),
                teams()));
    }

    private OptionalInt teams() {
        return switch (sleeper.rosters()) {
            case SourceResult.Ok<List<SleeperAdapter.Roster>> ok -> ok.value().isEmpty()
                    ? OptionalInt.empty() : OptionalInt.of(ok.value().size());
            case SourceResult.Unavailable<List<SleeperAdapter.Roster>> unavailable ->
                OptionalInt.empty();
        };
    }
}
