package otto.ask;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JavaType;
import org.springframework.stereotype.Component;

import otto.storage.JsonStore;
import otto.storage.OttoJson;

/**
 * The rolling context the Ask loop carries between questions: the last
 * day of conversation, capped at the last twenty messages. Both bounds
 * matter - the cap keeps a busy Sunday inside one prompt, and the day
 * keeps last week's start/sit talk from steering this week's answer.
 */
@Component
public class ConversationStore {

    private static final String DOCUMENT = "conversation";
    private static final Duration WINDOW = Duration.ofHours(24);
    private static final int MAX_MESSAGES = 20;

    private static final JavaType LIST_TYPE =
            OttoJson.MAPPER.getTypeFactory().constructCollectionType(List.class, Turn.class);

    private final JsonStore store;

    public ConversationStore(JsonStore store) {
        this.store = store;
    }

    /** One message in the conversation, from the user or the assistant. */
    public record Turn(Role role, String text, Instant at) {

        public enum Role {
            USER, ASSISTANT
        }
    }

    /** The messages the next Ask carries as context. */
    public List<Turn> window(Instant now) {
        Instant oldest = now.minus(WINDOW);
        return newest(all().stream()
                .filter(turn -> turn.at().isAfter(oldest))
                .toList());
    }

    /** Records one exchange: what the user asked and what Otto answered. */
    public synchronized void record(Instant now, String question, String answer) {
        List<Turn> turns = new ArrayList<>(all());
        turns.add(new Turn(Turn.Role.USER, question, now));
        turns.add(new Turn(Turn.Role.ASSISTANT, answer, now));
        // The stored document stays bounded by the same cap the window
        // reads with: nothing beyond it can ever come back.
        store.write(DOCUMENT, newest(turns));
    }

    private List<Turn> newest(List<Turn> turns) {
        return turns.size() <= MAX_MESSAGES
                ? turns
                : List.copyOf(turns.subList(turns.size() - MAX_MESSAGES, turns.size()));
    }

    private List<Turn> all() {
        return store.<List<Turn>>read(DOCUMENT, LIST_TYPE).orElseGet(List::of);
    }
}
