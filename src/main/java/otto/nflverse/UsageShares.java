package otto.nflverse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How much of his own offence each player owned, game by game.
 *
 * <p>A share is what a rank is not: self-normalising. A top-24 finish
 * moves with the week, because a blowout or a pass-heavy script
 * reshuffles the busiest two dozen players at a position without any
 * role changing. A share of the same team's own work in the same game
 * does not move that way, so it can be read against a fixed bar and
 * needs no rule about how thin the week's table is.
 *
 * <p>The two positions groups get the share that describes them:
 *
 * <ul>
 * <li>Receivers and tight ends get <b>target share</b>, which nflverse
 * publishes per player-week as {@code target_share} - his targets over
 * his team's targets in that game.</li>
 * <li>Running backs get <b>opportunity share</b>: his carries plus his
 * targets over the same total for every back his team played that week.
 * nflverse publishes no such column, so it is computed here, from the
 * carries and targets already downloaded.</li>
 * </ul>
 *
 * <p>Quarterbacks get neither. Nothing a quarterback does divides into
 * a share of his own offence, and no waiver tag rests on one.
 */
public final class UsageShares {

    /** The four-position feed splits into pass catchers and backs. */
    private static final String BACK = "RB";
    private static final List<String> PASS_CATCHERS = List.of("WR", "TE");

    public static final String TARGET_SHARE = "target share";
    public static final String OPPORTUNITY_SHARE = "opportunity share";

    private final Map<String, Player> byPlayer;
    private final int newestWeek;

    private UsageShares(Map<String, Player> byPlayer, int newestWeek) {
        this.byPlayer = byPlayer;
        this.newestWeek = newestWeek;
    }

    /** One game's share, and the week it was played. */
    public record Game(int week, double share) {
    }

    /**
     * One player's shares across the season, oldest first, and the name
     * of the share so the board can say which one it read.
     */
    public record Player(String kind, List<Game> games) {
    }

    /** The newest week any of these rows was played in. */
    public int newestWeek() {
        return newestWeek;
    }

    public Optional<Player> of(String gsisId) {
        return Optional.ofNullable(byPlayer.get(gsisId));
    }

    /**
     * Reads a stored weekly-stats feed into shares. The team totals are
     * taken from the same rows, so the denominator is a real team's
     * real game and never a league-wide average.
     */
    public static UsageShares of(WeeklyStats stats) {
        Map<String, Double> backTouches = new LinkedHashMap<>();
        stats.rows().stream()
                .filter(line -> BACK.equals(line.position()))
                .forEach(line -> backTouches.merge(teamWeek(line), line.touches(), Double::sum));

        Map<String, List<Game>> games = new LinkedHashMap<>();
        Map<String, String> kinds = new LinkedHashMap<>();
        int newest = 0;
        for (WeeklyStats.StatLine line : stats.rows()) {
            newest = Math.max(newest, line.week());
            Optional<Double> share = shareOf(line, backTouches);
            if (share.isEmpty()) {
                continue;
            }
            kinds.putIfAbsent(line.gsisId(),
                    BACK.equals(line.position()) ? OPPORTUNITY_SHARE : TARGET_SHARE);
            games.computeIfAbsent(line.gsisId(), id -> new ArrayList<>())
                    .add(new Game(line.week(), share.get()));
        }

        Map<String, Player> byPlayer = new LinkedHashMap<>();
        games.forEach((gsisId, played) -> {
            played.sort(Comparator.comparingInt(Game::week));
            byPlayer.put(gsisId, new Player(kinds.get(gsisId), List.copyOf(played)));
        });
        return new UsageShares(Map.copyOf(byPlayer), newest);
    }

    private static Optional<Double> shareOf(WeeklyStats.StatLine line,
            Map<String, Double> backTouches) {
        if (PASS_CATCHERS.contains(line.position())) {
            return Optional.ofNullable(line.targetShare());
        }
        if (!BACK.equals(line.position())) {
            return Optional.empty();
        }
        double team = backTouches.getOrDefault(teamWeek(line), 0.0);
        // A back who touched the ball in a game his team is recorded as
        // never handing off has no share to read, not a share of zero.
        return team > 0.0 ? Optional.of(line.touches() / team) : Optional.empty();
    }

    private static String teamWeek(WeeklyStats.StatLine line) {
        return line.team() + "|" + line.week();
    }
}
