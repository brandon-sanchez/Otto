package otto.records;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * How the run ended: one message sent, a message held for the next
 * waiver board, or nothing sent and why.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "result")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Outcome.Sent.class, name = "sent"),
        @JsonSubTypes.Type(value = Outcome.HeldForBoard.class, name = "held-for-board"),
        @JsonSubTypes.Type(value = Outcome.Withheld.class, name = "withheld")})
public sealed interface Outcome {

    /** @param message the text the user received */
    record Sent(String message) implements Outcome {
    }

    /**
     * Checked advice that waits for the next waiver board, because
     * sending it alone would not let the user act any sooner.
     *
     * @param message the text the board carries
     * @param signature what makes later advice about the same player the
     *        same advice, kept here so a run repeated from this record
     *        holds it again with the same signature; null in a record
     *        that keeps none
     */
    record HeldForBoard(String message,
            @JsonInclude(JsonInclude.Include.NON_NULL) String signature) implements Outcome {
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
