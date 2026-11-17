package otto.team;

import java.util.Optional;

/**
 * One player a team can use over the rest of the season, rated by his
 * per-game average.
 *
 * @param perGame his rest-of-season projected points per game he plays,
 *        in league scoring: the remaining weekly projections summed and
 *        divided by the games left, byes left out
 * @param starter true when he is in his team's best lineup by that
 *        average
 * @param boomChance the chance, from 0 to 1, that one of his weeks
 *        reaches his position's boom line; empty for a position without
 *        one
 */
public record RatedPlayer(
        String playerId,
        String name,
        String position,
        double perGame,
        boolean starter,
        Optional<Double> boomChance) {
}
