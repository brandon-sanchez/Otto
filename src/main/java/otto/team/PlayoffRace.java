package otto.team;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import otto.ask.LeagueAnalysis;
import otto.ask.LeagueWeek;
import otto.snapshot.RosterSnapshot;

/**
 * How far each team sits from the league's playoff field, in games:
 * a team in a spot by its lead over the first team out, a team outside
 * by its deficit to the last team in. Every roster counts, with players
 * or not, because each one holds a place in the standings.
 *
 * @param seeded every roster in seed order, as the standings seed them
 * @param playoffTeams how many teams make the playoffs, fewer than the league holds
 */
record PlayoffRace(List<RosterSnapshot> seeded, int playoffTeams) {

    PlayoffRace {
        seeded = List.copyOf(seeded);
    }

    /**
     * Returns the race when the league document names a playoff field
     * that leaves somebody out; empty otherwise, since a field of every
     * team says nothing about contention.
     */
    static Optional<PlayoffRace> of(LeagueWeek league) {
        int playoffTeams = league.league().playoffTeams();
        if (playoffTeams <= 0 || playoffTeams >= league.rosters().size()) {
            return Optional.empty();
        }
        return Optional.of(new PlayoffRace(
                league.rosters().stream().sorted(LeagueAnalysis.BY_SEED).toList(),
                playoffTeams));
    }

    /**
     * Returns the team's standing from 0 to 1: 0.5 level on wins with
     * the playoff line, 1 for a lead as large as the games left, which
     * nobody can make up, and 0 for a deficit that large. Each game ahead
     * or behind counts for less the more games are left.
     *
     * @param gamesLeft the regular-season games the team has left
     */
    double placing(RosterSnapshot team, int gamesLeft) {
        return Math.clamp(0.5 + margin(team) / (2.0 * Math.max(1, gamesLeft)), 0.0, 1.0);
    }

    /**
     * Returns where the team stands against the playoff line in words,
     * such as "a game out of the last of the 6 playoff spots".
     */
    String describe(RosterSnapshot team) {
        double margin = margin(team);
        boolean inASpot = seed(team) <= playoffTeams;
        if (margin == 0) {
            return inASpot
                    ? "level on wins with the first team out of the %d playoff spots"
                            .formatted(playoffTeams)
                    : "level on wins with the last of the %d playoff spots"
                            .formatted(playoffTeams);
        }
        return inASpot
                ? "%s clear of the first team out of the %d playoff spots".formatted(
                        games(margin), playoffTeams)
                : "%s out of the last of the %d playoff spots".formatted(games(-margin),
                        playoffTeams);
    }

    /**
     * Returns the team's lead over the first team out when it holds a
     * spot, or minus its deficit to the last team in when it doesn't, in
     * games, a tie counted as half a win.
     */
    private double margin(RosterSnapshot team) {
        boolean inASpot = seed(team) <= playoffTeams;
        RosterSnapshot line = seeded.get(inASpot ? playoffTeams : playoffTeams - 1);
        return wins(team) - wins(line);
    }

    private int seed(RosterSnapshot team) {
        for (int index = 0; index < seeded.size(); index++) {
            if (seeded.get(index).rosterId() == team.rosterId()) {
                return index + 1;
            }
        }
        throw new IllegalArgumentException("roster %d is not in the league"
                .formatted(team.rosterId()));
    }

    private static double wins(RosterSnapshot team) {
        return team.teamRecord().wins() + team.teamRecord().ties() / 2.0;
    }

    private static String games(double count) {
        if (count == 0.5) {
            return "half a game";
        }
        if (count == 1) {
            return "a game";
        }
        return count == Math.rint(count)
                ? "%d games".formatted((long) count)
                : String.format(Locale.ROOT, "%.1f games", count);
    }
}
