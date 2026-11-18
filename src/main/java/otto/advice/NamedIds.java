package otto.advice;

import java.util.List;
import java.util.Map;

/**
 * Reads a player the model wrote by name where an id belongs. Models
 * slip between the two, and an exact full name that only one known
 * player has says which player was meant as surely as his id does.
 */
public final class NamedIds {

    private NamedIds() {
    }

    /**
     * Returns the player id a value stands for.
     *
     * @param value what the model wrote in an id field; may be null
     * @param namesById the players the field may refer to, id to full name
     * @return the id of the one player whose full name {@code value} is
     *         exactly; otherwise {@code value} unchanged, whether it is
     *         already an id, matches no name, or is a name two players
     *         share, so the caller's own check still rejects what it
     *         does not know
     */
    public static String resolve(String value, Map<String, String> namesById) {
        if (value == null || namesById.containsKey(value)) {
            return value;
        }
        List<String> matches = namesById.entrySet().stream()
                .filter(player -> value.equals(player.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        return matches.size() == 1 ? matches.getFirst() : value;
    }
}
