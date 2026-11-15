package otto.records;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * How the run ended: one message sent, a waiver board sent, a message
 * built for a dry run and kept unsent, a message held for the next
 * waiver board, nothing sent and why, or a run that never had a message
 * to send.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "result")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Outcome.Sent.class, name = "sent"),
        @JsonSubTypes.Type(value = Outcome.SentBoard.class, name = "sent-board"),
        @JsonSubTypes.Type(value = Outcome.DryRun.class, name = "dry-run"),
        @JsonSubTypes.Type(value = Outcome.HeldForBoard.class, name = "held-for-board"),
        @JsonSubTypes.Type(value = Outcome.Withheld.class, name = "withheld"),
        @JsonSubTypes.Type(value = Outcome.Silent.class, name = "silent")})
public sealed interface Outcome {

    /** @param message the text the user received */
    record Sent(String message) implements Outcome {
    }

    /**
     * A waiver board sent to the user. Advice held for the board goes
     * out under it and is recorded in its own runs, not here.
     *
     * @param messages the Telegram messages the board went out as, in
     *        order, each inside Telegram's length limit
     * @param allOptions every entry the board ranks, as the messages its
     *        View all options button sends; empty when the board shows
     *        them all and has no such button
     */
    record SentBoard(List<String> messages, List<String> allOptions) implements Outcome {

        public SentBoard {
            messages = List.copyOf(messages);
            allOptions = List.copyOf(allOptions);
        }
    }

    /**
     * A message built in full and recorded so it can be reviewed, which
     * by design never reached the user.
     *
     * @param messages the Telegram messages it would have gone out as,
     *        in order, each inside Telegram's length limit
     */
    record DryRun(List<String> messages) implements Outcome {

        public DryRun {
            messages = List.copyOf(messages);
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
     * Nothing the run produced reached the user.
     *
     * @param reason why, for whoever reads the record
     * @param notice the message sent in its place to tell the user
     *        nothing is coming, or null when the run sends nothing at
     *        all
     */
    record Withheld(String reason, @JsonInclude(JsonInclude.Include.NON_NULL) String notice)
            implements Outcome {

        /** A withheld run that sends nothing in its place. */
        public Withheld(String reason) {
            this(reason, null);
        }
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
