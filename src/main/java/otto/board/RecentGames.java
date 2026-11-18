package otto.board;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import otto.directory.DirectoryPlayer;
import otto.nflverse.NflverseStore;
import otto.nflverse.PlayerIdMap;
import otto.nflverse.PlayerUsage;
import otto.nflverse.WeeklyStats;

/**
 * Each free agent's most recent games for one board: nflverse's, with
 * the latest week merged in from Sleeper's stats where {@link SleeperFill}
 * fills his team's late feed. Every game says which of its parts came
 * from Sleeper, so the lanes label their evidence by source without
 * merging anything themselves.
 *
 * <p>nflverse stays the source for every part it holds: a box score is
 * taken from Sleeper only when nflverse has no line of his that week,
 * and snaps or targets and carries only when nflverse's game lacks them.
 *
 * <p>The usage is read once per board, because it parses the stored
 * play-by-play and charting.
 */
final class RecentGames {

    /** How many of a player's most recent games the evidence shows. */
    static final int COUNT = 3;

    /** A part of a game Sleeper can give in place of nflverse. */
    enum Part {
        SNAPS("snaps"),
        VOLUME("targets and carries");

        private final String words;

        Part(String words) {
            this.words = words;
        }

        /**
         * Returns the parts in plain words, such as "snaps and targets
         * and carries"; an {@link EnumSet} keeps them in that order.
         */
        static String join(Set<Part> parts) {
            return parts.stream().map(part -> part.words).collect(Collectors.joining(" and "));
        }
    }

    /**
     * One game of a player's role and opportunity.
     *
     * @param fromSleeper the parts Sleeper gave; empty for a game
     *        nflverse gave in full
     */
    record Usage(PlayerUsage.Game game, Set<Part> fromSleeper) {
    }

    /**
     * One game's box score in the weekly stats' vocabulary.
     *
     * @param opponent the team he played; null when unknown
     * @param fromSleeper true when the whole line is Sleeper's
     */
    record Production(int week, Map<String, Double> stats, String opponent,
            boolean fromSleeper) {
    }

    private final Optional<PlayerUsage> usage;
    private final Optional<WeeklyStats> weeklyStats;
    private final Optional<PlayerIdMap> ids;
    private final SleeperFill sleeper;
    private final Map<String, List<WeeklyStats.StatLine>> lines;
    private final int latestWeek;

    private RecentGames(Optional<PlayerUsage> usage, Optional<WeeklyStats> weeklyStats,
            Optional<PlayerIdMap> ids, SleeperFill sleeper) {
        this.usage = usage;
        this.weeklyStats = weeklyStats;
        this.ids = ids;
        this.sleeper = sleeper;
        this.lines = weeklyStats
                .map(feed -> feed.rows().stream()
                        .collect(Collectors.groupingBy(WeeklyStats.StatLine::gsisId)))
                .orElse(Map.of());
        this.latestWeek = Math.max(sleeper.week(), weeklyStats
                .flatMap(feed -> feed.rows().stream()
                        .map(WeeklyStats.StatLine::week)
                        .max(Integer::compare))
                .orElse(0));
    }

    /**
     * Reads the stored nflverse feeds once and merges in what Sleeper fills.
     *
     * <p>Side effects: reads the stored weekly stats, player-id map, and
     * every feed {@link PlayerUsage} is built from.
     *
     * @param sleeper what Sleeper fills of the latest week,
     *        {@link SleeperFill#NONE} when nothing
     */
    static RecentGames read(NflverseStore nflverse, SleeperFill sleeper) {
        return new RecentGames(PlayerUsage.from(nflverse), nflverse.weeklyStats(),
                nflverse.playerIds(), sleeper);
    }

    /** Returns the stored usage feeds, empty when no weekly stats are stored. */
    Optional<PlayerUsage> usage() {
        return usage;
    }

    /** Returns the stored weekly stats, empty when none are stored. */
    Optional<WeeklyStats> weeklyStats() {
        return weeklyStats;
    }

    /** Returns whether a player-id map is stored, without which no game joins a player. */
    boolean idsKnown() {
        return ids.isPresent();
    }

    /** Returns the latest week either nflverse or Sleeper holds; 0 when neither holds one. */
    int latestWeek() {
        return latestWeek;
    }

    /** Returns the player's last {@link #COUNT} games of role and opportunity, oldest first. */
    List<Usage> usage(DirectoryPlayer player) {
        List<Usage> all = new ArrayList<>(usage.flatMap(held -> gsisId(player).map(held::games))
                .orElse(List.of())
                .stream()
                .map(game -> new Usage(game, Set.of()))
                .toList());
        filledUsage(player, all).ifPresent(filled -> {
            all.removeIf(held -> held.game().week() == filled.game().week());
            all.add(filled);
            all.sort(Comparator.comparingInt(held -> held.game().week()));
        });
        return List.copyOf(last(all, COUNT));
    }

    /**
     * Returns the player's last {@link #COUNT} box scores, oldest first,
     * with Sleeper's line for the week it fills when nflverse has none
     * of his that week.
     */
    List<Production> production(DirectoryPlayer player) {
        List<WeeklyStats.StatLine> held = gsisId(player)
                .map(gsis -> lines.getOrDefault(gsis, List.of()))
                .orElse(List.of())
                .stream()
                .sorted(Comparator.comparingInt(WeeklyStats.StatLine::week))
                .toList();
        boolean heldThatWeek = held.stream().anyMatch(line -> line.week() == sleeper.week());
        Optional<Map<String, Double>> filled = heldThatWeek ? Optional.empty()
                : sleeper.stats(player);
        List<Production> games = new ArrayList<>(last(held,
                filled.isPresent() ? COUNT - 1 : COUNT).stream()
                .map(line -> new Production(line.week(), line.stats(), line.opponent(), false))
                .toList());
        filled.ifPresent(stats -> games.add(new Production(sleeper.week(), stats,
                sleeper.opponent(player), true)));
        return List.copyOf(games);
    }

    /**
     * Returns the player's game in the week Sleeper fills, with each part
     * nflverse lacks taken from Sleeper; empty when Sleeper gives nothing
     * nflverse lacks.
     */
    private Optional<Usage> filledUsage(DirectoryPlayer player, List<Usage> held) {
        if (sleeper.week() == 0) {
            return Optional.empty();
        }
        PlayerUsage.Game game = held.stream()
                .map(Usage::game)
                .filter(candidate -> candidate.week() == sleeper.week())
                .findFirst()
                .orElseGet(() -> new PlayerUsage.Game(gsisId(player).orElse(player.playerId()),
                        player.fullName(), player.position(), player.team(), sleeper.week(),
                        null, null, null, null, null));
        Optional<PlayerUsage.Snaps> snaps = game.snaps() == null
                ? sleeper.snaps(player) : Optional.empty();
        Optional<PlayerUsage.Volume> volume = game.volume() == null
                ? sleeper.volume(player) : Optional.empty();
        Set<Part> fromSleeper = EnumSet.noneOf(Part.class);
        snaps.ifPresent(ignored -> fromSleeper.add(Part.SNAPS));
        volume.ifPresent(ignored -> fromSleeper.add(Part.VOLUME));
        if (fromSleeper.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Usage(new PlayerUsage.Game(game.gsisId(), game.player(),
                game.position(), game.team(), game.week(), snaps.orElse(game.snaps()),
                volume.orElse(game.volume()), game.receiving(), game.opportunities(),
                game.expected()), Collections.unmodifiableSet(fromSleeper)));
    }

    private Optional<String> gsisId(DirectoryPlayer player) {
        return ids.flatMap(map -> map.gsisFor(player.playerId()));
    }

    private static <T> List<T> last(List<T> games, int count) {
        return games.subList(Math.max(0, games.size() - count), games.size());
    }
}
