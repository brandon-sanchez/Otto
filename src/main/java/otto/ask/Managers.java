package otto.ask;

import java.util.List;
import java.util.function.Function;

import otto.advice.ToolAnswer;
import otto.league.LeagueWeek;
import otto.snapshot.RosterSnapshot;

/** Resolves the league mate a chat tool names, or says why it can't. */
final class Managers {

    private Managers() {
    }

    /**
     * Answers about the one manager the reference names.
     *
     * @return unavailable, with the league's teams or the matches, when
     *         the reference names nobody or more than one manager
     */
    static <T> ToolAnswer<T> named(LeagueWeek league, String reference,
            Function<RosterSnapshot, ToolAnswer<T>> answer) {
        List<RosterSnapshot> matches = league.managersMatching(reference);
        if (matches.isEmpty()) {
            return ToolAnswer.unavailable(
                    "no manager in this league matches \"%s\"; the teams are %s".formatted(
                            reference,
                            league.rosters().stream().map(RosterSnapshot::manager).toList()));
        }
        if (matches.size() > 1) {
            return ToolAnswer.unavailable("\"%s\" matches more than one manager: %s".formatted(
                    reference, matches.stream().map(RosterSnapshot::manager).toList()));
        }
        return answer.apply(matches.getFirst());
    }
}
