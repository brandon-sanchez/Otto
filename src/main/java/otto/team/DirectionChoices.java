package otto.team;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JavaType;
import org.springframework.stereotype.Component;

import otto.storage.JsonStore;
import otto.storage.OttoJson;

/**
 * The direction the user gave his own team in chat, stored per league
 * and used in place of the measured one until he changes or clears it,
 * and the seasons Otto has asked him whether he is rebuilding.
 */
@Component
public class DirectionChoices {

    private static final String DOCUMENT = "team-directions";

    private static final String QUESTIONS = "team-direction-questions";

    private static final JavaType LIST_TYPE = OttoJson.MAPPER.getTypeFactory()
            .constructCollectionType(List.class, Stored.class);

    private static final JavaType ASKED_TYPE = OttoJson.MAPPER.getTypeFactory()
            .constructCollectionType(List.class, Asked.class);

    private final JsonStore store;

    public DirectionChoices(JsonStore store) {
        this.store = store;
    }

    record Stored(String leagueId, Direction direction) {
    }

    /** The season Otto asked the user whether his team in the league is rebuilding. */
    record Asked(String leagueId, String season) {
    }

    /** Returns true when Otto asked about rebuilding in the league this season. */
    public boolean askedAboutRebuilding(String leagueId, String season) {
        return asked().contains(new Asked(leagueId, season));
    }

    /**
     * Remembers that Otto asked whether the user is rebuilding, so the
     * question is not asked again this season.
     *
     * <p>Side effects: writes the stored questions.
     */
    public synchronized void rememberAskedAboutRebuilding(String leagueId, String season) {
        List<Asked> asked = new ArrayList<>(askedElsewhere(leagueId));
        asked.add(new Asked(leagueId, season));
        store.write(QUESTIONS, asked);
    }

    /**
     * Forgets that Otto asked about rebuilding in the league, so it asks
     * again the next time the question applies.
     *
     * <p>Side effects: writes the stored questions when there was one.
     */
    public synchronized void forgetAskedAboutRebuilding(String leagueId) {
        List<Asked> kept = askedElsewhere(leagueId);
        if (kept.size() != asked().size()) {
            store.write(QUESTIONS, kept);
        }
    }

    private List<Asked> askedElsewhere(String leagueId) {
        return asked().stream().filter(asked -> !asked.leagueId().equals(leagueId)).toList();
    }

    private List<Asked> asked() {
        return store.<List<Asked>>read(QUESTIONS, ASKED_TYPE).orElseGet(List::of);
    }

    /** Returns the direction the user chose for his team in the league, if he chose one. */
    public Optional<Direction> chosen(String leagueId) {
        return all().stream()
                .filter(stored -> stored.leagueId().equals(leagueId))
                .map(Stored::direction)
                .findFirst();
    }

    /**
     * Stores the user's direction for the league, replacing any earlier one.
     *
     * <p>Side effects: writes the stored document.
     */
    public synchronized void set(String leagueId, Direction direction) {
        List<Stored> leagues = new ArrayList<>(others(leagueId));
        leagues.add(new Stored(leagueId, direction));
        store.write(DOCUMENT, leagues);
    }

    /**
     * Forgets the user's direction for the league, so the measured one
     * applies again.
     *
     * <p>Side effects: writes the stored document.
     */
    public synchronized void clear(String leagueId) {
        store.write(DOCUMENT, others(leagueId));
    }

    private List<Stored> others(String leagueId) {
        return all().stream().filter(stored -> !stored.leagueId().equals(leagueId)).toList();
    }

    private List<Stored> all() {
        return store.<List<Stored>>read(DOCUMENT, LIST_TYPE).orElseGet(List::of);
    }
}
