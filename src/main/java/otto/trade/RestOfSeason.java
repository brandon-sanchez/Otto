package otto.trade;

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
 * @param notes what a reader must know about the total: a week whose
 *        projections or schedule never arrived, and how far it reaches
 */
public record RestOfSeason(
        List<Integer> weeks,
        Map<String, Double> points,
        Map<String, List<Integer>> byeWeeks,
        Map<String, Integer> weeksPriced,
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

    /** How many weeks the total covers, byes included. */
    public int weekCount() {
        return weeks.size();
    }
}
