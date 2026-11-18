package otto.ask;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JavaType;
import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.scope.LeagueScope;
import otto.storage.JsonStore;
import otto.storage.OttoJson;

/**
 * The rolling context the Ask loop carries between questions: the last
 * day of conversation, capped at the last twenty messages. Both bounds
 * matter - the cap keeps a busy Sunday inside one prompt, and the day
 * keeps last week's start/sit talk from steering this week's answer.
 *
 * <p>Each turn notes the league it was about, and the window holds only
 * the turns about the league in {@link LeagueScope}, so talk about one
 * league's roster never steers an answer about another's. A turn whose
 * answer is the same in every league, such as a player's snap share, is
 * about every league and shows in every window.
 */
@Component
public class ConversationStore {

    private static final String DOCUMENT = "conversation";
    private static final Duration WINDOW = Duration.ofHours(24);
    private static final int MAX_MESSAGES = 20;

    /** What a stored turn notes as its league when its answer is the same in every league. */
    private static final String EVERY_LEAGUE = "*";

    private static final JavaType LIST_TYPE = OttoJson.MAPPER.getTypeFactory()
            .constructCollectionType(List.class, StoredTurn.class);

    private final JsonStore store;
    private final String configLeagueId;

    public ConversationStore(JsonStore store, OttoProperties properties) {
        this.store = store;
        this.configLeagueId = properties.leagueId();
    }

    /**
     * One message in the conversation, from the user or the assistant.
     *
     * @param league the league the exchange was about, or every league
     *        when its answer is the same in every league
     */
    public record Turn(Role role, String text, Instant at, League league) {

        public enum Role {
            USER, ASSISTANT
        }

        /** Which league an exchange was about. */
        public sealed interface League {

            /** The answer is the same in every league. */
            record EveryLeague() implements League {
            }

            /** The answer depends on this one league. */
            record One(String leagueId) implements League {
            }
        }

        /** Returns whether the exchange was about every league rather than one. */
        public boolean aboutEveryLeague() {
            return league instanceof League.EveryLeague;
        }
    }

    /**
     * A turn as the conversation document stores it. The league stays a
     * plain string field, so every deployed version reads the document.
     *
     * @param leagueId the league's id, or {@link #EVERY_LEAGUE}; null in
     *        a turn written without a league, which reads as the config
     *        league
     */
    private record StoredTurn(Turn.Role role, String text, Instant at, String leagueId) {
    }

    /**
     * The messages the next Ask about the league in scope carries as
     * context: the turns about that league and the turns about every
     * league.
     */
    public List<Turn> window(Instant now) {
        Turn.League league = new Turn.League.One(leagueInScope());
        return recent(now, turn -> turn.aboutEveryLeague() || turn.league().equals(league));
    }

    /**
     * The messages the next Ask carries as context while no league is
     * chosen: only the turns about every league, so no one league's
     * roster talk steers the answer.
     */
    public List<Turn> windowForEveryLeague(Instant now) {
        return recent(now, Turn::aboutEveryLeague);
    }

    /** Records one exchange about the league in scope: what the user asked and what Otto answered. */
    public void record(Instant now, String question, String answer) {
        record(now, question, answer, new Turn.League.One(leagueInScope()));
    }

    /** Records one exchange whose answer is the same in every league. */
    public void recordForEveryLeague(Instant now, String question, String answer) {
        record(now, question, answer, new Turn.League.EveryLeague());
    }

    private synchronized void record(Instant now, String question, String answer,
            Turn.League league) {
        List<Turn> turns = new ArrayList<>(all());
        turns.add(new Turn(Turn.Role.USER, question, now, league));
        turns.add(new Turn(Turn.Role.ASSISTANT, answer, now, league));
        // The stored document stays bounded by the same cap the window
        // reads with: nothing beyond it can ever come back.
        store.write(DOCUMENT, newest(turns).stream().map(ConversationStore::stored).toList());
    }

    private List<Turn> recent(Instant now, Predicate<Turn> shown) {
        Instant oldest = now.minus(WINDOW);
        return newest(all().stream()
                .filter(turn -> turn.at().isAfter(oldest))
                .filter(shown)
                .toList());
    }

    private List<Turn> newest(List<Turn> turns) {
        return turns.size() <= MAX_MESSAGES
                ? turns
                : List.copyOf(turns.subList(turns.size() - MAX_MESSAGES, turns.size()));
    }

    private String leagueInScope() {
        return LeagueScope.bound().orElse(configLeagueId);
    }

    private List<Turn> all() {
        return store.<List<StoredTurn>>read(DOCUMENT, LIST_TYPE).orElseGet(List::of).stream()
                .map(stored -> new Turn(stored.role(), stored.text(), stored.at(),
                        league(stored.leagueId())))
                .toList();
    }

    private Turn.League league(String storedId) {
        if (EVERY_LEAGUE.equals(storedId)) {
            return new Turn.League.EveryLeague();
        }
        return new Turn.League.One(storedId == null ? configLeagueId : storedId);
    }

    private static StoredTurn stored(Turn turn) {
        return new StoredTurn(turn.role(), turn.text(), turn.at(), switch (turn.league()) {
            case Turn.League.EveryLeague _ -> EVERY_LEAGUE;
            case Turn.League.One one -> one.leagueId();
        });
    }
}
