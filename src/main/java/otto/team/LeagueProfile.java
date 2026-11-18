package otto.team;

import java.util.List;
import java.util.Optional;

import otto.lineup.PlayerPositions;

/**
 * Every team with players in the league, profiled the same way so each
 * position can be ranked across them.
 *
 * @param teams every team with at least one player, in roster order
 */
public record LeagueProfile(List<TeamProfile> teams) {

    /** The positions a team profile covers, in the order an answer reads them. */
    public static final List<String> POSITIONS = PlayerPositions.SKILL;

    public LeagueProfile {
        teams = List.copyOf(teams);
    }

    /** Returns the user's team, empty when no profiled team is his. */
    public Optional<TeamProfile> user() {
        return teams.stream().filter(TeamProfile::userTeam).findFirst();
    }
}
