package otto.team;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One team's roster read position by position against the league,
 * bench and activatable IR or taxi players included.
 *
 * @param positions QB, RB, WR and TE, in that order
 * @param lineup the team's best lineup by per-game average
 * @param boomStarters how many of the lineup's starters reach a boom
 *        week in an average week: the sum of their boom chances
 * @param boomRank 1 for the league's highest {@code boomStarters}
 */
public record TeamProfile(
        int rosterId,
        String manager,
        boolean userTeam,
        Map<String, PositionProfile> positions,
        List<RatedPlayer> lineup,
        double boomStarters,
        int boomRank) {

    public TeamProfile {
        positions = Map.copyOf(positions);
        lineup = List.copyOf(lineup);
    }

    /** Returns the profile at a position, empty for one the league does not start. */
    public Optional<PositionProfile> at(String position) {
        return Optional.ofNullable(positions.get(position));
    }
}
