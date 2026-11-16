package otto.records;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * How the run ended: one message sent, a message built for a dry run
 * and kept unsent, a message held for the next waiver board, nothing
 * sent and why, or a run that never had a message to send.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "result")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Outcome.Sent.class, name = "sent"),
        @JsonSubTypes.Type(value = Outcome.DryRun.class, name = "dry-run"),
        @JsonSubTypes.Type(value = Outcome.HeldForBoard.class, name = "held-for-board"),
        @JsonSubTypes.Type(value = Outcome.Withheld.class, name = "withheld"),
        @JsonSubTypes.Type(value = Outcome.Silent.class, name = "silent")})
public sealed interface Outcome {

    /** @param message the text the user received */
    record Sent(String message) implements Outcome {
    }

    /**
     * One claim a waiver board recommended, as the board comparison
     * judges it.
     *
     * @param drop the id of the rostered player the claim replaces,
     *        empty when an open roster spot takes him
     */
    record BoardPick(String playerId, Optional<String> drop) {

        public BoardPick {
            drop = drop == null ? Optional.empty() : drop;
        }
    }

    /**
     * A message built in full and recorded so it can be reviewed, which
     * by design never reached the user.
     *
     * @param messages the Telegram messages it would have gone out as,
     *        in order, each inside Telegram's length limit
     * @param picks the claims a dry-run waiver board recommended, best
     *        first, and none when it told the user to hold; empty in a
     *        record that keeps none, whose picks are read back from its
     *        exchanges
     */
    record DryRun(List<String> messages, Optional<List<BoardPick>> picks) implements Outcome {

        public DryRun {
            messages = List.copyOf(messages);
            picks = picks == null ? Optional.empty() : picks.map(List::copyOf);
        }
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

    /**
     * A run that by design has no message, such as the baseline boards
     * kept only to judge the waiver board against.
     *
     * @param reason why the run was never meant to send anything
     */
    record Silent(String reason) implements Outcome {
    }
}
