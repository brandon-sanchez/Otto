package otto.ask;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JavaType;
import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.sleeper.LeagueScope;
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
 * league's roster never steers an answer about another's.
 */
@Component
public class ConversationStore {

    private static final String DOCUMENT = "conversation";
    private static final Duration WINDOW = Duration.ofHours(24);
    private static final int MAX_MESSAGES = 20;

    private static final JavaType LIST_TYPE =
            OttoJson.MAPPER.getTypeFactory().constructCollectionType(List.class, Turn.class);

    private final JsonStore store;
    private final String configLeagueId;

    public ConversationStore(JsonStore store, OttoProperties properties) {
        this.store = store;
        this.configLeagueId = properties.leagueId();
    }

    /**
     * One message in the conversation, from the user or the assistant.
     *
     * @param leagueId the league the exchange was about; null in a turn
     *        stored before turns named their league, which was about the
     *        config league
     */
    public record Turn(Role role, String text, Instant at, String leagueId) {

        public enum Role {
            USER, ASSISTANT
        }
    }

    /** The messages about the league in scope that the next Ask carries as context. */
    public List<Turn> window(Instant now) {
        Instant oldest = now.minus(WINDOW);
        String league = leagueInScope();
        return newest(all().stream()
                .filter(turn -> turn.at().isAfter(oldest))
                .filter(turn -> league.equals(
                        turn.leagueId() == null ? configLeagueId : turn.leagueId()))
                .toList());
    }

    /** Records one exchange about the league in scope: what the user asked and what Otto answered. */
    public synchronized void record(Instant now, String question, String answer) {
        String league = leagueInScope();
        List<Turn> turns = new ArrayList<>(all());
        turns.add(new Turn(Turn.Role.USER, question, now, league));
        turns.add(new Turn(Turn.Role.ASSISTANT, answer, now, league));
        // The stored document stays bounded by the same cap the window
        // reads with: nothing beyond it can ever come back.
        store.write(DOCUMENT, newest(turns));
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
        return store.<List<Turn>>read(DOCUMENT, LIST_TYPE).orElseGet(List::of);
    }
}
