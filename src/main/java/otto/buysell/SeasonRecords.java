package otto.buysell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import otto.lineup.PlayerPositions;
import otto.nflverse.ExpectedPoints;
import otto.nflverse.NflverseStore;

/**
 * Every running back, receiver and tight end's games this season and
 * last season, read from the two stored ffopportunity files, with each
 * player's finish at his position last season.
 */
public final class SeasonRecords {

    /** The positions the buy-low and sell-high rules cover. */
    public static final List<String> POSITIONS = PlayerPositions.FLEX;

    /** A last season shorter than this says too little about a player's normal level. */
    static final int FULL_SEASON_GAMES = 8;

    private final String season;
    private final Optional<String> lastSeason;
    private final Map<String, PlayerSeason> current;
    private final Map<String, PlayerSeason> previous;
    private final Map<String, Integer> finishes;
    private final Map<String, Double> positionMeans;

    private SeasonRecords(String season, Optional<String> lastSeason,
            Map<String, PlayerSeason> current, Map<String, PlayerSeason> previous) {
        this.season = season;
        this.lastSeason = lastSeason;
        this.current = current;
        this.previous = previous;
        this.finishes = finishes(previous);
        this.positionMeans = positionMeans(previous);
    }

    /**
     * Reads both files from the store.
     *
     * @return empty until this season's file holds a played week, since
     *         every rule reads this season's games
     */
    public static Optional<SeasonRecords> from(NflverseStore store) {
        Optional<ExpectedPoints> thisSeason = store.expectedPoints()
                .filter(feed -> !feed.priorSeasonFinal());
        if (thisSeason.isEmpty()) {
            return Optional.empty();
        }
        String season = thisSeason.get().season();
        Optional<ExpectedPoints> last = store.expectedPointsLastSeason()
                .filter(feed -> !feed.season().equals(season));
        return Optional.of(new SeasonRecords(season, last.map(ExpectedPoints::season),
                seasons(thisSeason.get()), last.map(SeasonRecords::seasons).orElse(Map.of())));
    }

    /** The season being played, such as "2026". */
    public String season() {
        return season;
    }

    /** The season before it, empty when its file is not stored. */
    public Optional<String> lastSeason() {
        return lastSeason;
    }

    /** Returns his games this season, empty when he has none. */
    public Optional<PlayerSeason> current(String gsisId) {
        return Optional.ofNullable(current.get(gsisId));
    }

    /**
     * Returns his games last season when he played enough of them to set
     * a normal level, empty otherwise.
     */
    public Optional<PlayerSeason> fullLastSeason(String gsisId) {
        return Optional.ofNullable(previous.get(gsisId))
                .filter(played -> played.gamesPlayed() >= FULL_SEASON_GAMES);
    }

    /** Returns where his total points ranked at his position last season, 1 for the most. */
    public Optional<Integer> lastSeasonFinish(String gsisId) {
        return Optional.ofNullable(finishes.get(gsisId));
    }

    /**
     * Returns the average points per game last season of every player at
     * the position who played a full season, empty without last season.
     */
    public Optional<Double> lastSeasonAverage(String position) {
        return Optional.ofNullable(positionMeans.get(position));
    }

    private static Map<String, PlayerSeason> seasons(ExpectedPoints feed) {
        Map<String, List<PlayerSeason.Game>> games = new HashMap<>();
        Map<String, String> positions = new HashMap<>();
        feed.rows().stream()
                .filter(line -> POSITIONS.contains(line.position()))
                .filter(line -> line.fantasyPoints() != null
                        && line.expectedFantasyPoints() != null)
                .sorted(Comparator.comparingInt(ExpectedPoints.Line::week))
                .forEach(line -> {
                    positions.putIfAbsent(line.gsisId(), line.position());
                    games.computeIfAbsent(line.gsisId(), id -> new ArrayList<>())
                            .add(new PlayerSeason.Game(line.week(), line.team(),
                                    line.fantasyPoints(), line.expectedFantasyPoints(),
                                    orZero(line.touchdowns()),
                                    orZero(line.expectedTouchdowns()), line.targets(),
                                    line.teamTargets(), line.carries(), line.teamCarries()));
                });
        return games.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                entry -> new PlayerSeason(entry.getKey(), positions.get(entry.getKey()),
                        entry.getValue())));
    }

    private static double orZero(Double value) {
        return value == null ? 0 : value;
    }

    private static Map<String, Integer> finishes(Map<String, PlayerSeason> seasons) {
        Map<String, Integer> ranks = new HashMap<>();
        seasons.values().stream()
                .collect(Collectors.groupingBy(PlayerSeason::position))
                .values()
                .forEach(group -> {
                    List<PlayerSeason> ordered = group.stream()
                            .sorted(Comparator.comparingDouble(PlayerSeason::totalPoints)
                                    .reversed()
                                    .thenComparing(PlayerSeason::gsisId))
                            .toList();
                    for (int at = 0; at < ordered.size(); at++) {
                        ranks.put(ordered.get(at).gsisId(), at + 1);
                    }
                });
        return Map.copyOf(ranks);
    }

    private static Map<String, Double> positionMeans(Map<String, PlayerSeason> seasons) {
        return seasons.values().stream()
                .filter(played -> played.gamesPlayed() >= FULL_SEASON_GAMES)
                .collect(Collectors.groupingBy(PlayerSeason::position,
                        Collectors.averagingDouble(PlayerSeason::pointsPerGame)));
    }
}
