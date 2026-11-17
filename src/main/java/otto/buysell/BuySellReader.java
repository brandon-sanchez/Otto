package otto.buysell;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.directory.DirectoryPlayer;
import otto.directory.PlayerDirectory;
import otto.directory.PlayerDirectoryStore;
import otto.directory.PlayerHealth;
import otto.market.MarketDay;
import otto.market.MarketFormat;
import otto.market.MarketPlayer;
import otto.market.MarketValues;
import otto.nflverse.DepthCharts;
import otto.nflverse.NflverseStore;
import otto.nflverse.PlayerIdMap;
import otto.nflverse.Schedule;
import otto.nflverse.SnapCounts;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;

/**
 * Gathers what the buy-low and sell-high rules read about a league's
 * players: both seasons of ffopportunity, the schedule's quarterbacks
 * and coaches, the newest depth charts, this season's snap counts, the
 * Player Directory's injury designations, and the league format's
 * FantasyCalc list for each player's 30-day value trend and average
 * draft position.
 */
@Component
public class BuySellReader {

    private static final String SOURCE = "ffopportunity";
    private static final int TREND_DAYS = 30;

    private final NflverseStore nflverse;
    private final PlayerDirectoryStore directory;
    private final MarketValues values;

    public BuySellReader(NflverseStore nflverse, PlayerDirectoryStore directory,
            MarketValues values) {
        this.nflverse = nflverse;
        this.directory = directory;
        this.values = values;
    }

    /**
     * Reads everything once for one league.
     *
     * <p>Side effects: at most one FantasyCalc request a day for the
     * league's format, through {@link MarketValues}. Without a market
     * list, players are judged on usage alone.
     *
     * @return the board, or unavailable before any game of this season
     *         has expected points or without the player-id map
     */
    public SourceResult<Board> board(SleeperAdapter.League league, Instant now) {
        Optional<SeasonRecords> seasons = SeasonRecords.from(nflverse);
        if (seasons.isEmpty()) {
            return new SourceResult.Unavailable<>(SOURCE,
                    "no game of this season has expected points yet");
        }
        Optional<PlayerIdMap> ids = nflverse.playerIds();
        if (ids.isEmpty()) {
            return new SourceResult.Unavailable<>("player-id map",
                    "the map that joins Sleeper players to ffopportunity is not stored yet");
        }
        List<MarketFormat> formats = MarketFormat.neededBy(league);
        Optional<Market> market = formats.isEmpty() ? Optional.empty()
                : switch (values.fantasyCalc(formats.getFirst(), now)) {
                    case SourceResult.Ok<MarketValues.Listing<MarketDay>> ok ->
                            Optional.of(new Market(formats.getFirst(), ok.value().values()));
                    case SourceResult.Unavailable<MarketValues.Listing<MarketDay>> none ->
                            Optional.empty();
                };
        return new SourceResult.Ok<>(new Board(seasons.get(), ids.get(),
                nflverse.schedule(), nflverse.depthCharts(),
                snapShares(seasons.get().season()),
                directory.read().map(PlayerDirectory::players).orElse(Map.of()), market));
    }

    private record Market(MarketFormat format, MarketDay day) {
    }

    /** This season's snap shares by Pro Football Reference id, oldest game first. */
    private Map<String, List<Double>> snapShares(String season) {
        Map<String, List<SnapCounts.SnapLine>> byPlayer = new HashMap<>();
        nflverse.snapCounts()
                .filter(snaps -> !snaps.priorSeasonFinal() && snaps.season().equals(season))
                .ifPresent(snaps -> snaps.rows().forEach(line -> byPlayer
                        .computeIfAbsent(line.pfrId(), id -> new ArrayList<>()).add(line)));
        Map<String, List<Double>> shares = new HashMap<>();
        byPlayer.forEach((pfrId, lines) -> shares.put(pfrId, lines.stream()
                .sorted(Comparator.comparingInt(SnapCounts.SnapLine::week))
                .map(SnapCounts.SnapLine::offensePct)
                .toList()));
        return shares;
    }

    /** Everything read for one league, ready to judge any of its players. */
    public final class Board {

        private final SeasonRecords seasons;
        private final PlayerIdMap ids;
        private final Optional<Schedule> schedule;
        private final Optional<DepthCharts> depthCharts;
        private final Map<String, List<Double>> snaps;
        private final Map<String, DirectoryPlayer> players;
        private final Optional<Market> market;

        private Board(SeasonRecords seasons, PlayerIdMap ids, Optional<Schedule> schedule,
                Optional<DepthCharts> depthCharts, Map<String, List<Double>> snaps,
                Map<String, DirectoryPlayer> players, Optional<Market> market) {
            this.seasons = seasons;
            this.ids = ids;
            this.schedule = schedule;
            this.depthCharts = depthCharts;
            this.snaps = snaps;
            this.players = players;
            this.market = market;
        }

        /**
         * Judges one player.
         *
         * @return empty when he is not a running back, receiver or tight
         *         end, cannot be joined to ffopportunity, or has no game
         *         this season
         */
        public Optional<Assessment> assess(String sleeperId) {
            DirectoryPlayer player = players.get(sleeperId);
            if (player != null && !SeasonRecords.POSITIONS.contains(player.position())) {
                return Optional.empty();
            }
            return ids.gsisFor(sleeperId)
                    .flatMap(seasons::current)
                    .map(season -> Assessment.of(subject(sleeperId, player, season)));
        }

        private Assessment.Subject subject(String sleeperId, DirectoryPlayer player,
                PlayerSeason season) {
            Optional<PlayerSeason> last = seasons.fullLastSeason(season.gsisId());
            Optional<MarketPlayer> row = market.flatMap(list -> list.day().entry(sleeperId));
            TeamContext context = schedule
                    .map(games -> TeamContext.of(games, depthCharts, seasons.season(),
                            seasons.lastSeason(), season, last))
                    .orElse(TeamContext.UNKNOWN);
            return new Assessment.Subject(season, last,
                    seasons.lastSeasonFinish(season.gsisId()),
                    seasons.lastSeasonAverage(season.position()),
                    row.flatMap(MarketPlayer::adp),
                    Optional.ofNullable(player).flatMap(found -> injury(found.health())),
                    ids.pfrFor(sleeperId).map(pfrId -> snaps.getOrDefault(pfrId, List.of()))
                            .orElse(List.of()),
                    context,
                    move(sleeperId, row));
        }

        private Optional<MarketMove> move(String sleeperId, Optional<MarketPlayer> row) {
            Optional<MarketMove> trend = row.flatMap(listed -> listed.trend30Day()
                    .filter(change -> listed.value() - change > 0)
                    .map(change -> new MarketMove(
                            (int) Math.round(100.0 * change / (listed.value() - change)),
                            TREND_DAYS, "FantasyCalc's 30-day trend")));
            if (trend.isPresent() || market.isEmpty()) {
                return trend;
            }
            return values.change(market.get().format(), market.get().day(), sleeperId,
                            TREND_DAYS)
                    .map(change -> new MarketMove((int) change.percent(), change.days(),
                            "Otto's daily record of FantasyCalc's values"));
        }
    }

    private static Optional<String> injury(PlayerHealth health) {
        return switch (health) {
            case ACTIVE -> Optional.empty();
            case PROBABLE -> Optional.of("Probable");
            case QUESTIONABLE -> Optional.of("Questionable");
            case DOUBTFUL -> Optional.of("Doubtful");
            case OUT -> Optional.of("Out");
            case IR -> Optional.of("on injured reserve");
            case PUP -> Optional.of("on the physically unable to perform list");
            case SUSPENDED -> Optional.of("suspended");
            case NOT_ACTIVE, DID_NOT_REPORT, UNKNOWN -> Optional.of("not active");
        };
    }
}
