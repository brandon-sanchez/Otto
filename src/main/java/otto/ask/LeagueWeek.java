package otto.ask;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import otto.check.WeekFacts;
import otto.directory.NameMatch;
import otto.sleeper.LeagueRules;
import otto.sleeper.SleeperAdapter;
import otto.snapshot.RosterSnapshot;
import otto.snapshot.Snapshot;

/**
 * What an Ask tool needs to answer a question about the league rather
 * than about one team: the league document, every roster from the
 * latest Snapshot, and this week's facts.
 */
public record LeagueWeek(
        SleeperAdapter.League league,
        Snapshot snapshot,
        WeekFacts week) {

    private static final int LAST_NFL_WEEK = 18;

    public List<RosterSnapshot> rosters() {
        return snapshot.rosters();
    }

    /**
     * The last week of the regular season, which is the week before the
     * playoffs start. Empty when the league document does not say.
     */
    public OptionalInt lastRegularWeek() {
        return league.playoffWeekStart() > 1
                ? OptionalInt.of(league.playoffWeekStart() - 1)
                : OptionalInt.empty();
    }

    /**
     * The last week of the league's playoffs: one round for each halving
     * of the playoff field, each as long as the league's playoff rounds,
     * and never past week 18. A league that doesn't say how long its
     * rounds are plays one week each, Sleeper's default. Empty when the
     * league document doesn't say when the playoffs start or how many
     * teams make them.
     */
    public OptionalInt lastPlayoffWeek() {
        if (league.playoffWeekStart() <= 1 || league.playoffTeams() < 2) {
            return OptionalInt.empty();
        }
        int rounds = 32 - Integer.numberOfLeadingZeros(league.playoffTeams() - 1);
        int weeks = switch (league.rules().playoffRounds()
                .orElse(LeagueRules.PlayoffRounds.ONE_WEEK)) {
            case ONE_WEEK -> rounds;
            case TWO_WEEK_FINAL -> rounds + 1;
            case TWO_WEEKS -> 2 * rounds;
        };
        return OptionalInt.of(Math.min(LAST_NFL_WEEK, league.playoffWeekStart() + weeks - 1));
    }

    /**
     * Resolves a manager the user named against the league's teams. The
     * same matching rule as a player reference: an exact name wins over
     * a partial one, and ambiguity is never resolved by guessing.
     */
    public List<RosterSnapshot> managersMatching(String reference) {
        List<String> matches = NameMatch.resolve(reference,
                rosters().stream().map(roster -> String.valueOf(roster.rosterId())).toList(),
                rosterId -> rosterNamed(rosterId).map(RosterSnapshot::manager).orElse(null));
        return matches.stream().flatMap(rosterId -> rosterNamed(rosterId).stream()).toList();
    }

    private Optional<RosterSnapshot> rosterNamed(String rosterId) {
        return rosters().stream()
                .filter(roster -> String.valueOf(roster.rosterId()).equals(rosterId))
                .findFirst();
    }
}
