package otto.waivers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import otto.nflverse.UsageShares;

/**
 * Whether a free agent's own share of his offence says the role is his.
 *
 * <p>A breakout is a role change that outlasts the week, and what
 * predicts one is how much of the offence the player himself took, not
 * what happened to the man ahead of him. Kyren Williams took the Rams'
 * backfield in week 1 of 2023 with nobody hurt anywhere; Puka Nacua
 * took 39% of the Rams' targets in the same week. Both were league
 * winners, and neither is visible in a rule that reads an injury label.
 *
 * <p>So there are two lanes, and either one is enough.
 *
 * <ul>
 * <li>The <b>fast lane</b> asks one game at the elite bar. This is
 * where the league winners are, and waiting a second week means bidding
 * against everybody else who waited.</li>
 * <li>The <b>slow lane</b> asks two consecutive games at the bar of a
 * real role. This is the role that grows rather than arrives.</li>
 * </ul>
 *
 * <p>A rising three-week share rides in the reasons whatever the tag,
 * because a role growing in front of the user is worth showing him even
 * when no bar has been crossed yet.
 */
public final class BreakoutLanes {

    /**
     * The fast-lane bar for receivers and tight ends. A season target
     * share above 25% is the published line for an elite receiver -
     * fewer than eight men in the league sustain 30% - so one game at
     * that rate is the offence treating him as its first read.
     */
    static final double FAST_TARGET_SHARE = 0.25;

    /**
     * The fast-lane bar for running backs. A back with 65% of his
     * team's carries and targets is the lead back of a backfield, not
     * the better half of a committee: the published line for a
     * two-man committee's lead back is 65-70% of the carries.
     */
    static final double FAST_OPPORTUNITY_SHARE = 0.65;

    /**
     * The slow-lane bar for receivers and tight ends. A season target
     * share above 20% is associated with WR1 finishes; 18% over two
     * straight games is the week-to-week reading of the same line,
     * which is a real role rather than an elite one.
     */
    static final double SLOW_TARGET_SHARE = 0.18;

    /**
     * The slow-lane bar for running backs. Half a backfield's work is
     * the point at which a committee has a lead back at all.
     */
    static final double SLOW_OPPORTUNITY_SHARE = 0.50;

    /** How many straight games the slow lane asks for. */
    static final int SLOW_LANE_GAMES = 2;

    /** How many straight games a rising share is read over. */
    static final int TREND_GAMES = 3;

    private BreakoutLanes() {
    }

    /** What the lanes made of one candidate, and what to tell the user. */
    public record Read(boolean breakout, List<String> reasons) {

        static Read nothing() {
            return new Read(false, List.of());
        }
    }

    /**
     * Reads the lanes for one player.
     *
     * @param position his position; a quarterback has no share of his
     *        own offence to read, so no lane fires for one
     * @param newestWeek the newest week the stats feed holds. A player
     *        whose last game is older than that is not breaking out
     *        now, whatever he did in it.
     * @param shares his games, oldest first, or empty when the feed has
     *        no line for him
     */
    public static Read of(String position, int newestWeek, Optional<UsageShares.Player> shares) {
        Optional<Bars> bars = Bars.forPosition(position);
        if (bars.isEmpty() || shares.isEmpty()) {
            return Read.nothing();
        }
        List<UsageShares.Game> games = shares.get().games();
        String kind = shares.get().kind();
        if (games.isEmpty() || games.getLast().week() != newestWeek) {
            return Read.nothing();
        }

        List<String> reasons = new ArrayList<>();
        boolean breakout = false;
        UsageShares.Game latest = games.getLast();

        if (latest.share() >= bars.get().fast()) {
            breakout = true;
            reasons.add(("he took %s of %s in week %d, at or above the %s that marks an elite "
                    + "one, so the role is his on one game").formatted(
                            percent(latest.share()), kind, latest.week(),
                            percent(bars.get().fast())));
        } else if (straightGames(games, SLOW_LANE_GAMES)
                && games.get(games.size() - SLOW_LANE_GAMES).share() >= bars.get().slow()
                && latest.share() >= bars.get().slow()) {
            breakout = true;
            reasons.add(("he held %s and then %s of %s over weeks %d and %d, above the %s that "
                    + "marks a real role in both").formatted(
                            percent(games.get(games.size() - SLOW_LANE_GAMES).share()),
                            percent(latest.share()), kind,
                            games.get(games.size() - SLOW_LANE_GAMES).week(), latest.week(),
                            percent(bars.get().slow())));
        }

        rising(games).ifPresent(reasons::add);
        return new Read(breakout, List.copyOf(reasons));
    }

    /**
     * A share that grew every week across the last three games. It has
     * preceded a run of top finishes more reliably than any single box
     * score line, so it is worth saying out loud even when it has not
     * yet crossed a bar.
     */
    private static Optional<String> rising(List<UsageShares.Game> games) {
        if (!straightGames(games, TREND_GAMES)) {
            return Optional.empty();
        }
        List<UsageShares.Game> window = games.subList(games.size() - TREND_GAMES, games.size());
        for (int index = 1; index < window.size(); index++) {
            if (window.get(index).share() <= window.get(index - 1).share()) {
                return Optional.empty();
            }
        }
        return Optional.of("his share has risen every week: %s".formatted(
                String.join(", ", window.stream()
                        .map(game -> "week %d %s".formatted(game.week(), percent(game.share())))
                        .toList())));
    }

    /** True when the last {@code count} games were played in straight weeks. */
    private static boolean straightGames(List<UsageShares.Game> games, int count) {
        if (games.size() < count) {
            return false;
        }
        List<UsageShares.Game> window = games.subList(games.size() - count, games.size());
        for (int index = 1; index < window.size(); index++) {
            if (window.get(index).week() - window.get(index - 1).week() != 1) {
                return false;
            }
        }
        return true;
    }

    /** The two bars one position is read against. */
    private record Bars(double fast, double slow) {

        static Optional<Bars> forPosition(String position) {
            return switch (position) {
                case "WR", "TE" ->
                    Optional.of(new Bars(FAST_TARGET_SHARE, SLOW_TARGET_SHARE));
                case "RB" ->
                    Optional.of(new Bars(FAST_OPPORTUNITY_SHARE, SLOW_OPPORTUNITY_SHARE));
                default -> Optional.empty();
            };
        }
    }

    private static String percent(double share) {
        return String.format(Locale.ROOT, "%.0f%%", share * 100.0);
    }
}
