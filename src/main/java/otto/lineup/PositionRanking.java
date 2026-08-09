package otto.lineup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import otto.directory.DirectoryPlayer;
import otto.directory.PlayerDirectory;

/**
 * The weekly projection table ranked within each position: who is the
 * best quarterback this week, who is the 24th running back, and what
 * the player at any rank projects.
 *
 * Two rules read from here. A Notable Player is one inside his
 * position's cutoff rank, which is what makes a drop league news.
 * Replacement level is what the player at the cutoff rank projects,
 * which is what makes a starting slot a gap.
 *
 * Only players the Player Directory keeps are ranked - quarterbacks,
 * running backs, receivers and tight ends - and only those the table
 * prices. A player with no projection holds no rank rather than the
 * last one.
 */
public final class PositionRanking {

    private final Map<String, List<String>> byPosition;
    private final Map<String, Double> points;
    private final Map<String, Integer> ranks;

    private PositionRanking(Map<String, List<String>> byPosition, Map<String, Double> points,
            Map<String, Integer> ranks) {
        this.byPosition = byPosition;
        this.points = points;
        this.ranks = ranks;
    }

    public static PositionRanking of(ProjectionTable table, PlayerDirectory directory) {
        Map<String, Double> points = new HashMap<>();
        Map<String, List<String>> byPosition = new HashMap<>();
        for (DirectoryPlayer player : directory.players().values()) {
            table.points(player.playerId(), player.position()).ifPresent(projected -> {
                points.put(player.playerId(), projected);
                byPosition.computeIfAbsent(player.position(), position -> new ArrayList<>())
                        .add(player.playerId());
            });
        }

        Map<String, Integer> ranks = new HashMap<>();
        byPosition.forEach((position, playerIds) -> {
            playerIds.sort(Comparator
                    .comparingDouble((String playerId) -> points.get(playerId))
                    .reversed()
                    // A tie must not rank by hash order: two players on the
                    // same projection would swap places between Checks.
                    .thenComparing(Comparator.naturalOrder()));
            for (int index = 0; index < playerIds.size(); index++) {
                ranks.put(playerIds.get(index), index + 1);
            }
        });
        return new PositionRanking(byPosition, points, ranks);
    }

    /** Where this player sits among his position, best first. */
    public OptionalInt rankOf(String playerId) {
        Integer rank = ranks.get(playerId);
        return rank == null ? OptionalInt.empty() : OptionalInt.of(rank);
    }

    /** How many players of this position the table prices. */
    public int ranked(String position) {
        return byPosition.getOrDefault(position, List.of()).size();
    }

    /**
     * What the player at this rank projects. Empty when the table does
     * not reach that far down the position, which is a shallow table
     * rather than a replacement level of zero.
     */
    public Optional<Double> pointsAtRank(String position, int rank) {
        List<String> playerIds = byPosition.getOrDefault(position, List.of());
        if (rank < 1 || rank > playerIds.size()) {
            return Optional.empty();
        }
        return Optional.of(points.get(playerIds.get(rank - 1)));
    }

    public Optional<Double> pointsOf(String playerId) {
        return Optional.ofNullable(points.get(playerId));
    }

    /** True when the player is inside his position's cutoff rank. */
    public boolean inside(String playerId, String position, PositionCutoffs cutoffs) {
        int cutoff = cutoffs.forPosition(position);
        OptionalInt rank = rankOf(playerId);
        return cutoff > 0 && rank.isPresent() && rank.getAsInt() <= cutoff;
    }
}
