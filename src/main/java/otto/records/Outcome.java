package otto.records;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** How the run ended: one message sent, or nothing sent and why. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "result")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Outcome.Sent.class, name = "sent"),
        @JsonSubTypes.Type(value = Outcome.Withheld.class, name = "withheld")})
public sealed interface Outcome {

    /** @param message the text the user received */
    record Sent(String message) implements Outcome {
    }

    /**
     * Nothing the run produced reached the user, and nothing is sent in
     * its place.
     *
     * @param reason why, for whoever reads the record
     */
    record Withheld(String reason) implements Outcome {
    }
}
