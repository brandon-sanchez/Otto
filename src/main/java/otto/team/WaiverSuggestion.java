package otto.team;

/**
 * Claim a free agent at a thin position on the user's team.
 *
 * @param need the user's thin position the free agent plays
 * @param add the free agent, best per-game average there
 * @param place where he would sit among the user's players at that
 *        position, 1 for the best
 */
public record WaiverSuggestion(PositionProfile need, RatedPlayer add, int place) {
}
