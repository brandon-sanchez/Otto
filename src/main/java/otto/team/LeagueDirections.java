package otto.team;

import java.util.List;
import java.util.Optional;

/**
 * Every team's direction in one league.
 *
 * @param teams every team with players, most likely contender first
 * @param market the market list the values come from, such as
 *        "12-team Superflex PPR redraft"; empty when no list could be
 *        read and the directions rest on record and points alone
 * @param gamesPlayed the most games any team has played
 */
public record LeagueDirections(List<TeamDirection> teams, Optional<String> market,
        int gamesPlayed) {

    public LeagueDirections {
        teams = List.copyOf(teams);
    }

    /** Returns the user's team, empty when no team with players is his. */
    public Optional<TeamDirection> user() {
        return teams.stream().filter(TeamDirection::userTeam).findFirst();
    }
}
