package otto.records;

import java.util.List;

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
     */
    record HeldForBoard(String message) implements Outcome {
    }

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
