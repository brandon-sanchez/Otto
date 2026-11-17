package otto.ask;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.storage.JsonStore;

/**
 * Which league the chat is about, and the question waiting on the
 * owner's answer to "which league?". Both last as long as the Ask
 * loop's conversation window, so yesterday's league does not quietly
 * answer today's question.
 */
@Component
public class ChatLeague {

    private static final String DOCUMENT = "chat-league";

    private static final Duration LASTS = Duration.ofHours(24);

    private final JsonStore store;

    public ChatLeague(JsonStore store) {
        this.store = store;
    }

    /**
     * The stored state.
     *
     * @param leagueId the league the last answer was about; null when it
     *        covered every league or no answer has been given
     * @param waiting the question Otto asked "which league?" about; null
     *        when none is waiting
     */
    record State(String leagueId, String waiting, Instant at) {
    }

    /** Returns the league the conversation is about, or empty when that is unclear. */
    public Optional<String> current(Instant now) {
        return state(now).map(State::leagueId);
    }

    /** Returns the question waiting on the owner to say which league, if any. */
    public Optional<String> waiting(Instant now) {
        return state(now).map(State::waiting);
    }

    /** Remembers that the conversation is about this league, and that nothing waits. */
    public void answered(String leagueId, Instant now) {
        store.write(DOCUMENT, new State(leagueId, null, now));
    }

    /** Remembers that the last answer covered every league, so the next league is unclear. */
    public void answeredEveryLeague(Instant now) {
        store.write(DOCUMENT, new State(null, null, now));
    }

    /** Remembers the question that waits on the owner's choice of league. */
    public void waitFor(String question, Instant now) {
        store.write(DOCUMENT, new State(null, question, now));
    }

    private Optional<State> state(Instant now) {
        return store.read(DOCUMENT, State.class)
                .filter(state -> state.at().isAfter(now.minus(LASTS)));
    }
}
