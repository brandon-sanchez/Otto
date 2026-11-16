package otto.decisions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import otto.nflverse.PlayerUsage;

/**
 * A real drop in a player's role: in his latest game his share of his
 * team's snaps, or of its targets and carries, fell well below what it
 * was in the games before. Points never enter into it, so a quiet box
 * score on a steady workload is not a drop.
 *
 * <p>A drop is real only when both conditions hold, so one noisy game
 * from a part-time player is not one: the share fell by at least
 * {@link #MIN_SHARE_LOST} of the team's work, and to at most
 * {@link #MAX_SHARE_KEPT} of what it was. A share a source does not
 * hold is unknown and never counts as a fall.
 *
 * @param week the week of the game the share fell in
 * @param measure which share fell
 * @param before his share in each earlier game, by week, from 0 to 1
 * @param latest his share in that game, from 0 to 1
 */
public record UsageDrop(int week, Measure measure, Map<Integer, Double> before, double latest) {

    /** A share of his team's work a drop is measured in. */
    public enum Measure {
        SNAP_SHARE("snap share"),
        OPPORTUNITY_SHARE("opportunity share");

        private final String label;

        Measure(String label) {
            this.label = label;
        }

        /** Returns the measure as the evidence writes it. */
        public String label() {
            return label;
        }
    }

    /** The fewest earlier games a drop is measured against. */
    static final int MIN_GAMES_BEFORE = 2;

    /** The most earlier games a drop is measured against, newest first. */
    static final int MAX_GAMES_BEFORE = 3;

    /** The least share of the team's work, from 0 to 1, a real drop loses. */
    static final double MIN_SHARE_LOST = 0.15;

    /** The most of his earlier share, as a fraction, a real drop leaves him. */
    static final double MAX_SHARE_KEPT = 0.75;

    /**
     * How many weeks his latest game may trail the newest week the stats
     * hold and still be current: one, for his bye.
     */
    static final int CURRENT_WITHIN_WEEKS = 1;

    public UsageDrop {
        before = Map.copyOf(before);
    }

    /**
     * Returns the drop in his latest game, empty when there is none or
     * the games do not show one either way.
     *
     * @param games his games this season, oldest first
     * @param newestWeek the newest week the weekly stats hold for any team
     */
    public static Optional<UsageDrop> in(List<PlayerUsage.Game> games, int newestWeek) {
        if (games.isEmpty() || games.getLast().week() < newestWeek - CURRENT_WITHIN_WEEKS) {
            return Optional.empty();
        }
        return measure(games, Measure.SNAP_SHARE, UsageDrop::snapShare)
                .or(() -> measure(games, Measure.OPPORTUNITY_SHARE, UsageDrop::opportunityShare));
    }

    private static Optional<UsageDrop> measure(List<PlayerUsage.Game> games, Measure measure,
            Function<PlayerUsage.Game, Optional<Double>> share) {
        PlayerUsage.Game latestGame = games.getLast();
        Optional<Double> latest = share.apply(latestGame);
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        Map<Integer, Double> before = new LinkedHashMap<>();
        List<PlayerUsage.Game> earlier = games.subList(0, games.size() - 1);
        for (int index = earlier.size() - 1; index >= 0 && before.size() < MAX_GAMES_BEFORE;
                index--) {
            PlayerUsage.Game game = earlier.get(index);
            share.apply(game).ifPresent(value -> before.put(game.week(), value));
        }
        if (before.size() < MIN_GAMES_BEFORE) {
            return Optional.empty();
        }
        double usual = before.values().stream().mapToDouble(Double::doubleValue).average()
                .orElseThrow();
        boolean fell = usual - latest.get() >= MIN_SHARE_LOST
                && latest.get() <= usual * MAX_SHARE_KEPT;
        return fell
                ? Optional.of(new UsageDrop(latestGame.week(), measure, before, latest.get()))
                : Optional.empty();
    }

    private static Optional<Double> snapShare(PlayerUsage.Game game) {
        return Optional.ofNullable(game.snaps()).map(PlayerUsage.Snaps::share);
    }

    /**
     * His targets and carries over his team's. A quarterback's work is
     * his snaps, so he has no opportunity share.
     */
    private static Optional<Double> opportunityShare(PlayerUsage.Game game) {
        PlayerUsage.Volume volume = game.volume();
        int teamWork = volume == null ? 0 : volume.teamTargets() + volume.teamCarries();
        if ("QB".equals(game.position()) || teamWork == 0) {
            return Optional.empty();
        }
        return Optional.of((volume.targets() + volume.carries()) / (double) teamWork);
    }

    /** The drop as the evidence writes it, shares as whole percents. */
    public Map<String, String> facts() {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("measure", measure.label());
        before.forEach((game, share) -> facts.put("week " + game, percent(share)));
        facts.put("week " + week, percent(latest));
        return facts;
    }

    private static String percent(double share) {
        return String.format(Locale.ROOT, "%.0f%%", share * 100);
    }
}
