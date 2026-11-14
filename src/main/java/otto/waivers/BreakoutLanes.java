package otto.waivers;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import otto.nflverse.RoleShares;

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

    static final double FAST_SNAP_SHARE = 0.70;

    /**
     * The slow-lane bar for receivers and tight ends. A season target
     * share above 20% is associated with WR1 finishes; 18% over two
     * straight games is the week-to-week reading of the same line,
     * which is a real role rather than an elite one.
     */
    static final double SLOW_TARGET_SHARE = 0.18;

    static final double SLOW_SNAP_SHARE = 0.55;

    static final double FAST_BACK_TARGET_SHARE = 0.25;

    static final double SLOW_BACK_TARGET_SHARE = 0.15;

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
     * Reads the lanes for one player. Receivers and tight ends are read by
     * target share. Running backs are read by snap share and by target
     * share, and either one can tag him.
     *
     * @param position his position; a quarterback has no share of his
     *        own offence to read, so no lane fires for one
     * @param shares his games, oldest first, or empty when the feed has
     *        no line for him
     */
    public static Read of(String position, Optional<RoleShares.Reading> shares) {
        if (shares.isEmpty()) {
            return Read.nothing();
        }
        RoleShares.Reading reading = shares.get();
        List<Lane> lanes = switch (position) {
            case "WR", "TE" -> List.of(new Lane(reading.targets(),
                    new Bars(FAST_TARGET_SHARE, SLOW_TARGET_SHARE)));
            case "RB" -> List.of(
                    new Lane(reading.snaps(), new Bars(FAST_SNAP_SHARE, SLOW_SNAP_SHARE)),
                    new Lane(reading.targets(),
                            new Bars(FAST_BACK_TARGET_SHARE, SLOW_BACK_TARGET_SHARE)));
            default -> List.of();
        };
        Set<String> reasons = new LinkedHashSet<>();
        boolean breakout = false;
        boolean snapsSaid = false;
        for (Lane lane : lanes) {
            Optional<LaneRead> read = lane.read();
            if (read.isEmpty()) {
                continue;
            }
            read.get().crossed().ifPresent(reasons::add);
            read.get().rising().ifPresent(reasons::add);
            if (read.get().crossed().isPresent()) {
                breakout = true;
                snapsSaid |= read.get().kind() == RoleShares.Kind.SNAP;
            }
        }
        if (breakout && !snapsSaid) {
            reading.latestSnap().map(BreakoutLanes::snapReason).ifPresent(reasons::add);
        }
        return new Read(breakout, List.copyOf(reasons));
    }

    private record Lane(Optional<RoleShares.Player> role, Bars bars) {

        Optional<LaneRead> read() {
            if (role.isEmpty()) {
                return Optional.empty();
            }
            RoleShares.Player player = role.get();
            List<RoleShares.Game> games = player.games();
            if (games.isEmpty() || games.getLast().week() != player.newestWeek()) {
                return Optional.empty();
            }
            return Optional.of(new LaneRead(player.kind(), crossed(player.kind(), games),
                    rising(player.kind(), games)));
        }

        private Optional<String> crossed(RoleShares.Kind kind, List<RoleShares.Game> games) {
            RoleShares.Game latest = games.getLast();
            if (latest.share() >= bars.fast()) {
                String read = kind == RoleShares.Kind.SNAP
                        ? "he played %s of the snaps in week %d"
                                .formatted(percent(latest.share()), latest.week())
                        : "he took %s of target share in week %d"
                                .formatted(percent(latest.share()), latest.week());
                return Optional.of(("%s, at or above the %s that marks an elite "
                        + "one, so the role is his on one game").formatted(
                                read, percent(bars.fast())));
            }
            if (!straightGames(games, SLOW_LANE_GAMES)) {
                return Optional.empty();
            }
            RoleShares.Game before = games.get(games.size() - SLOW_LANE_GAMES);
            if (before.share() < bars.slow() || latest.share() < bars.slow()) {
                return Optional.empty();
            }
            String read = kind == RoleShares.Kind.SNAP
                    ? "he played %s and then %s of the snaps over weeks %d and %d".formatted(
                            percent(before.share()), percent(latest.share()), before.week(),
                            latest.week())
                    : "he held %s and then %s of target share over weeks %d and %d".formatted(
                            percent(before.share()), percent(latest.share()), before.week(),
                            latest.week());
            return Optional.of(("%s, at or above the %s that marks a real role in both")
                    .formatted(read, percent(bars.slow())));
        }
    }

    private record LaneRead(RoleShares.Kind kind, Optional<String> crossed,
            Optional<String> rising) {
    }

    private static String snapReason(RoleShares.Game snap) {
        return "he played %s of the snaps in week %d"
                .formatted(percent(snap.share()), snap.week());
    }

    /**
     * A share that grew every week across the last three games. It has
     * preceded a run of top finishes more reliably than any single box
     * score line, so it is worth saying out loud even when it has not
     * yet crossed a bar.
     */
    private static Optional<String> rising(RoleShares.Kind kind, List<RoleShares.Game> games) {
        if (!straightGames(games, TREND_GAMES)) {
            return Optional.empty();
        }
        List<RoleShares.Game> window = games.subList(games.size() - TREND_GAMES, games.size());
        for (int index = 1; index < window.size(); index++) {
            if (window.get(index).share() <= window.get(index - 1).share()) {
                return Optional.empty();
            }
        }
        return Optional.of("his %s share has risen every week: %s".formatted(
                kind == RoleShares.Kind.SNAP ? "snap" : "target",
                String.join(", ", window.stream()
                        .map(game -> "week %d %s".formatted(game.week(), percent(game.share())))
                        .toList())));
    }

    /** True when the last {@code count} games were played in straight weeks. */
    private static boolean straightGames(List<RoleShares.Game> games, int count) {
        if (games.size() < count) {
            return false;
        }
        List<RoleShares.Game> window = games.subList(games.size() - count, games.size());
        for (int index = 1; index < window.size(); index++) {
            if (window.get(index).week() - window.get(index - 1).week() != 1) {
                return false;
            }
        }
        return true;
    }

    private record Bars(double fast, double slow) {
    }

    private static String percent(double share) {
        return String.format(Locale.ROOT, "%.0f%%", share * 100.0);
    }
}
