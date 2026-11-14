package otto.records;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * @param roster the user's roster as Otto read it
 * @param faabRemaining the waiver budget left, empty when Otto could
 *        not verify it - which is not the same as a budget of zero
 */
public record RosterContext(JsonNode roster, Optional<Integer> faabRemaining) {
}
