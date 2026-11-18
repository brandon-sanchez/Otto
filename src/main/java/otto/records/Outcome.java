package otto.records;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.jsontype.TypeDeserializer;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * How the run ended: one message sent, a checked chat answer handed to
 * the chat model, a waiver board sent, a message built for a dry run and
 * kept unsent, a message held for the next waiver board, nothing sent
 * and why, nothing sent but a notice saying so, or a run that never had
 * a message to send.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "result")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Outcome.Sent.class, name = "sent"),
        @JsonSubTypes.Type(value = Outcome.HandedToChat.class, name = "handed-to-chat"),
        @JsonSubTypes.Type(value = Outcome.SentBoard.class, name = "sent-board"),
        @JsonSubTypes.Type(value = Outcome.DryRun.class, name = "dry-run"),
        @JsonSubTypes.Type(value = Outcome.HeldForBoard.class, name = "held-for-board"),
        @JsonSubTypes.Type(value = Outcome.Withheld.class, name = "withheld"),
        @JsonSubTypes.Type(value = Outcome.WithheldWithNotice.class, name = "withheld-notice"),
        @JsonSubTypes.Type(value = Outcome.Silent.class, name = "silent")})
@JsonDeserialize(using = Outcome.Reader.class)
public sealed interface Outcome {

    /**
     * One message sent. A chat tool's checked answer recorded this way
     * reached the user unchanged, with no chat model reply written over it.
     *
     * @param message the text the user received, word for word
     */
    @JsonDeserialize(using = JsonDeserializer.None.class)
    record Sent(String message) implements Outcome {
    }

    /**
     * A chat tool's checked answer given to the chat model as a tool
     * result, because the model called other tools for the same
     * question. The user read the model's own reply, which no check
     * covered and which this record does not hold.
     *
     * @param message the checked text the model was given
     */
    @JsonDeserialize(using = JsonDeserializer.None.class)
    record HandedToChat(String message) implements Outcome {
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
     * A waiver board sent to the user. Advice held for the board goes
     * out under it and is recorded in its own runs, not here.
     *
     * @param messages the Telegram messages the board went out as, in
     *        order, each inside Telegram's length limit
     * @param allOptions every entry the board ranks, as the messages its
     *        View all options button sends; empty when the board shows
     *        them all and has no such button
     * @param picks the claims the board recommended, best first, and
     *        none when it told the user to hold; empty in a record that
     *        keeps none, whose picks are read back from its exchanges
     */
    @JsonDeserialize(using = JsonDeserializer.None.class)
    record SentBoard(List<String> messages, List<String> allOptions,
            Optional<List<BoardPick>> picks) implements Outcome {

        public SentBoard {
            messages = List.copyOf(messages);
            allOptions = List.copyOf(allOptions);
            picks = picks == null ? Optional.empty() : picks.map(List::copyOf);
        }
    }

    /**
     * A message built in full and recorded so it can be reviewed, which
     * by design never reached the user.
     *
     * @param messages the Telegram messages it would have gone out as,
     *        in order, each inside Telegram's length limit
     * @param picks the claims a dry-run waiver board recommended, as
     *        {@link SentBoard#picks} keeps them
     */
    @JsonDeserialize(using = JsonDeserializer.None.class)
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
    @JsonDeserialize(using = JsonDeserializer.None.class)
    record HeldForBoard(String message,
            @JsonInclude(JsonInclude.Include.NON_NULL) String signature) implements Outcome {
    }

    /**
     * Nothing the run produced reached the user, and nothing is sent in
     * its place.
     *
     * @param reason why, for whoever reads the record
     */
    @JsonDeserialize(using = JsonDeserializer.None.class)
    record Withheld(String reason) implements Outcome {
    }

    /**
     * Nothing the run produced reached the user, and a notice goes out
     * in its place to say nothing is coming, unless a later run for the
     * same thing takes its place first: a waiver board try that failed
     * its checks records the notice its round ends on, and the notice is
     * sent only when no later try of the round passes.
     *
     * @param reason why, for whoever reads the record
     * @param notice the message sent in its place
     */
    @JsonDeserialize(using = JsonDeserializer.None.class)
    record WithheldWithNotice(String reason, String notice) implements Outcome {
    }

    /**
     * A run that by design has no message, such as the baseline boards
     * kept only to judge the waiver board against.
     *
     * @param reason why the run was never meant to send anything
     */
    @JsonDeserialize(using = JsonDeserializer.None.class)
    record Silent(String reason) implements Outcome {
    }

    /**
     * Reads a stored outcome by its {@code result} name. A
     * {@code withheld} outcome that carries a {@code notice} is read as
     * {@link WithheldWithNotice}, the shape such a record takes.
     */
    final class Reader extends StdDeserializer<Outcome> {

        Reader() {
            super(Outcome.class);
        }

        @Override
        public Outcome deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            ObjectNode node = parser.readValueAsTree();
            String result = node.path("result").asText();
            if (result.equals("withheld") && node.hasNonNull("notice")) {
                result = "withheld-notice";
                node.put("result", result);
            }
            Class<? extends Outcome> type = switch (result) {
                case "sent" -> Sent.class;
                case "handed-to-chat" -> HandedToChat.class;
                case "sent-board" -> SentBoard.class;
                case "dry-run" -> DryRun.class;
                case "held-for-board" -> HeldForBoard.class;
                case "withheld" -> Withheld.class;
                case "withheld-notice" -> WithheldWithNotice.class;
                case "silent" -> Silent.class;
                default -> throw context.weirdStringException(result, Outcome.class,
                        "not a known outcome");
            };
            return context.readTreeAsValue(node, type);
        }

        @Override
        public Object deserializeWithType(JsonParser parser, DeserializationContext context,
                TypeDeserializer typeDeserializer) throws IOException {
            return deserialize(parser, context);
        }
    }
}
