package otto.ask;

import java.util.List;
import java.util.Optional;

import otto.followed.FollowedLeague;
import otto.followed.LeagueMentions;

/**
 * Which league a chat message is about, decided in code from the
 * message, the followed leagues and what the chat remembers (ADR-0012):
 *
 * <ol>
 *   <li>with one league followed, every message is about it;</li>
 *   <li>a message that names leagues is about them, and one that says
 *       "every league" is about every league;</li>
 *   <li>a short reply that only picks a league answers the question that
 *       waited on "which league?";</li>
 *   <li>otherwise the message is about the league the chat was last
 *       about;</li>
 *   <li>otherwise no league is chosen yet.</li>
 * </ol>
 *
 * <p>It reads and writes nothing, so the rules are tested on their own.
 */
final class LeagueChoice {

    private LeagueChoice() {
    }

    /** What to answer, and in which league or leagues. */
    sealed interface Choice {

        /**
         * Answer {@code question} in one league.
         *
         * @param named true when the message named the league, so the
         *        reply names it whatever it turns out to depend on
         */
        record InOne(FollowedLeague league, String question, boolean named, LeaguePick pick)
                implements Choice {
        }

        /**
         * Answer {@code question} in each of several leagues.
         *
         * @param waited true when the question waited on the owner's
         *        choice of league, so it is already known to depend on one
         */
        record InEach(List<FollowedLeague> leagues, String question, boolean waited)
                implements Choice {
            public InEach {
                leagues = List.copyOf(leagues);
            }
        }

        /** No league is chosen: answer only if the answer is the same in every league. */
        record NoneYet(String question) implements Choice {
        }
    }

    /**
     * Chooses the league or leagues a message is about.
     *
     * @param followed the followed leagues, never empty
     * @param waiting the question that waits on the owner's choice of
     *        league, if one does
     * @param chatLeagueId the league the chat was last about, if it is
     *        still about one
     */
    static Choice of(String message, List<FollowedLeague> followed, Optional<String> waiting,
            Optional<String> chatLeagueId) {
        if (followed.size() == 1) {
            return new Choice.InOne(followed.getFirst(), message, false,
                    LeagueMentions.everyLeague(message) ? LeaguePick.EVERY_LEAGUE_ASKED
                            : LeaguePick.ONLY_LEAGUE);
        }
        Optional<String> waited = waiting
                .filter(question -> LeagueMentions.onlyAChoice(message, followed));
        String question = waited.orElse(message);
        List<FollowedLeague> mentioned = LeagueMentions.named(message, followed);
        List<FollowedLeague> named = mentioned.isEmpty() && LeagueMentions.everyLeague(message)
                ? followed : mentioned;
        if (named.size() > 1) {
            return new Choice.InEach(named, question, waited.isPresent());
        }
        Optional<FollowedLeague> league = named.stream().findFirst()
                .or(() -> chatLeagueId.flatMap(id -> followed.stream()
                        .filter(candidate -> candidate.leagueId().equals(id))
                        .findFirst()));
        return league.<Choice>map(chosen -> new Choice.InOne(chosen, question,
                        !named.isEmpty(), LeaguePick.CHOSEN))
                .orElseGet(() -> new Choice.NoneYet(question));
    }
}
