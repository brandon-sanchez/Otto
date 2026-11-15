package otto.advice;

import java.util.Optional;

/**
 * A player the advice may name.
 *
 * @param projectedPoints this week's projection in league scoring, as
 *        the evidence writes it; empty when there is no projection,
 *        which is not a projection of zero
 */
public record BundlePlayer(String playerId, String fullName, Optional<String> projectedPoints) {
}
