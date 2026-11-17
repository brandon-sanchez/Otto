package otto.trade;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What every player left in the season is projected to score in this
 * league's own scoring, summed over the weeks still to play.
 *
 * A trade is a season-long decision, so it is priced over the season
 * that is left rather than over the coming week. A week the player's
 * team does not play counts zero, because a bye scores nothing and a
 * player with more byes ahead of him is worth less.
 *
 * @param weeks the weeks this total covers, first to last
 * @param points rest-of-season points per player, in league scoring
 * @param byeWeeks the weeks each player's team sits out, per player
 * @param weeksPriced how many of the weeks carried a projection for
 *        each player, so an answer can say what it could not see
 * @param weeksProjectedToScore how many of the weeks projected each
 *        player above zero: the games he is expected to play, which a
 *        week he is ruled out of or his team's bye never counts in
 * @param notes what a reader must know about the total: a week whose
 *        projections or schedule never arrived, and how far it reaches
 */
public record RestOfSeason(
        List<Integer> weeks,
        Map<String, Double> points,
        Map<String, List<Integer>> byeWeeks,
        Map<String, Integer> weeksPriced,
        Map<String, Integer> weeksProjectedToScore,
        List<String> notes) {

    public Optional<Double> points(String playerId) {
        return Optional.ofNullable(points.get(playerId));
    }

    public List<Integer> byesFor(String playerId) {
        return byeWeeks.getOrDefault(playerId, List.of());
    }

    public int weeksPricedFor(String playerId) {
        return weeksPriced.getOrDefault(playerId, 0);
    }

    /**
     * Returns what he is projected to score in a game he plays: his total
     * over the weeks projecting him above zero. A week Sleeper zeroes him
     * out for an injury doesn't drag it down.
     *
     * @return empty when no week projects him above zero
     */
    public Optional<Double> perGamePlayed(String playerId) {
        int games = weeksProjectedToScore.getOrDefault(playerId, 0);
        return games == 0 ? Optional.empty()
                : points(playerId).map(total -> total / games);
    }

    /**
     * Returns the same rest of season with some players' totals replaced,
     * such as an injured player's priced from his return. A replaced
     * total doesn't come from his weekly projections, so
     * {@link #perGamePlayed} is empty for him in the result.
     */
    public RestOfSeason withPoints(Map<String, Double> replaced) {
        Map<String, Double> merged = new HashMap<>(points);
        merged.putAll(replaced);
        Map<String, Integer> projected = new HashMap<>(weeksProjectedToScore);
        projected.keySet().removeAll(replaced.keySet());
        return new RestOfSeason(weeks, Map.copyOf(merged), byeWeeks, weeksPriced,
                Map.copyOf(projected), notes);
    }

    /** How many weeks the total covers, byes included. */
    public int weekCount() {
        return weeks.size();
    }
}
