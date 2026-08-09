package otto.ask;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import otto.check.WeekFacts;
import otto.directory.NameMatch;
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
