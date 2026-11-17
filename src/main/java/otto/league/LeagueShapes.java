package otto.league;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.lineup.LeagueStarters;
import otto.lineup.PositionCutoffs;
import otto.lineup.PositionRanking;
import otto.lineup.PositionRankings;
import otto.lineup.Slot;
import otto.snapshot.RosterSnapshot;

/**
 * Reads a league-week's {@link LeagueShape} and the weekly projection
 * table Replacement Level is priced from. Build the shape once per
 * league-week and hand it to everything that prices a player in it.
 */
@Component
public class LeagueShapes {

    private final LeagueStarters starters;
    private final PositionRankings rankings;
    private final PositionCutoffs fallback;

    public LeagueShapes(LeagueStarters starters, PositionRankings rankings,
            OttoProperties properties) {
        this.starters = starters;
        this.rankings = rankings;
        this.fallback = properties.replacementCutoffs();
    }

    /**
     * Returns the league's shape with each team's best lineup filled from
     * this week's projections: active players only, IR and taxi left out
     * because they cannot start. A team the snapshot holds no roster for
     * counts as filling every slot it can only fill from free agents.
     *
     * @return the shape from the league's slots alone when no projection
     *         table is read yet or no team has a projected player
     */
    public LeagueShape shape(LeagueWeek league) {
        LeagueShape bySlots = LeagueShape.of(league.league(), league.rosters().size(), fallback);
        if (league.week().projections().isEmpty()) {
            return bySlots;
        }
        List<Slot> slots = league.week().startingSlots().stream()
                .filter(Slot::modelledExactly)
                .toList();
        Map<String, String> positions = new HashMap<>();
        List<Map<String, Double>> teams = new ArrayList<>();
        for (RosterSnapshot roster : league.rosters()) {
            UserWeek team = new UserWeek(league.league(), roster, league.week());
            Map<String, Double> points = new LinkedHashMap<>();
            team.roster().players().stream()
                    .filter(playerId -> !roster.reserve().contains(playerId)
                            && !roster.taxi().contains(playerId))
                    .forEach(playerId -> team.points(playerId)
                            .ifPresent(projected -> points.put(playerId, projected)));
            positions.putAll(team.positions());
            teams.add(points);
        }
        teams.addAll(Collections.nCopies(Math.max(0, bySlots.teams() - teams.size()),
                Map.<String, Double>of()));
        Map<String, Integer> starting = starters.startersByPosition(slots, teams, positions);
        return starting.isEmpty() ? bySlots : bySlots.withStarters(starting);
    }

    /**
     * Returns this week's projection table ranked by position. Empty when
     * no table or player directory is read yet.
     */
    public Optional<PositionRanking> ranking(LeagueWeek league) {
        return league.week().projections().flatMap(rankings::rank);
    }
}
