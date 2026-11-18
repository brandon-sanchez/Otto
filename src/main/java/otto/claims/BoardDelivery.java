package otto.claims;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import otto.delivery.AlertDeliveryOutbox;
import otto.delivery.DeliveryBasis;
import otto.events.Event;
import otto.events.EventType;

/**
 * Sends a claim round's waiver board through the Alert outbox under the
 * round's Alert key, with the role-slip advice held for it underneath.
 * Whichever board the round sends, the held advice goes out word for
 * word as it passed its checks and is forgotten once Telegram accepts
 * the board.
 */
@Component
public class BoardDelivery {

    private static final String ABOUT_YOUR_ROSTER = "About your roster";

    private final AlertDeliveryOutbox outbox;
    private final RoleSlipLedger roleSlips;

    public BoardDelivery(AlertDeliveryOutbox outbox, RoleSlipLedger roleSlips) {
        this.outbox = outbox;
        this.roleSlips = roleSlips;
    }

    /**
     * True once Telegram has accepted a board for this round, or the
     * notice that none is coming.
     */
    public boolean alreadySent(ClaimRound round) {
        return outbox.alreadySent(round.alertKey());
    }

    /**
     * Sends a board of ready-made messages.
     *
     * <p>Side effects: the outbox's writes and Telegram sends, the Event
     * Log append, and a role-slip ledger write when held advice went out.
     *
     * @param basis what the board's messages were written from
     * @param withHeldAdvice lays out the board's messages with the held
     *        advice messages under them
     * @param viewAllOptions whether View all options hangs under the board
     * @param facts the facts the Alert Event records; the held runs are
     *        added here and the sent text by the outbox
     * @return the Event recorded when Telegram accepted the board
     */
    public Optional<Event> deliver(ClaimRound round, DeliveryBasis basis,
            Function<List<String>, List<String>> withHeldAdvice, boolean viewAllOptions,
            Map<String, String> facts) {
        List<RoleSlipLedger.Held> held = roleSlips.heldForBoard();
        List<String> parts = withHeldAdvice.apply(messages(held));
        return delivered(held, outbox.deliver(parts, viewAllOptions, withHeld(basis, held),
                templates(round, facts, held)));
    }

    /**
     * Sends a board written in one message by a model call. A retry of
     * a delivery still pending from the same basis sends the text
     * already written, at no further model call.
     *
     * <p>Side effects: as {@link #deliver}, plus the phrasing call when
     * no pending text matches.
     *
     * @param basis what the phrasing was written from
     * @param phrase writes the board's text
     */
    public Optional<Event> deliverPhrased(ClaimRound round, DeliveryBasis basis,
            Supplier<String> phrase, Map<String, String> facts) {
        List<RoleSlipLedger.Held> held = roleSlips.heldForBoard();
        return delivered(held, outbox.deliver(withHeld(basis, held),
                () -> withHeldAdvice(phrase.get(), messages(held)),
                templates(round, facts, held)));
    }

    private static List<Event> templates(ClaimRound round, Map<String, String> facts,
            List<RoleSlipLedger.Held> held) {
        Map<String, String> recorded = new HashMap<>(facts);
        if (!held.isEmpty()) {
            recorded.put("heldAdviceRuns", String.join(",", runs(held)));
        }
        return List.of(new Event(round.alertKey(), EventType.ALERT_SENT, round.now(),
                Map.copyOf(recorded)));
    }

    private Optional<Event> delivered(List<RoleSlipLedger.Held> held, List<Event> sent) {
        Optional<Event> delivered = sent.stream().findFirst();
        if (delivered.isPresent() && !held.isEmpty()) {
            roleSlips.deliveredInBoard(runs(held));
        }
        return delivered;
    }

    /**
     * What a board's messages were written from: its own basis and the
     * runs of the held advice it carries, so the same board with
     * different advice under it counts as a new delivery.
     */
    private static DeliveryBasis withHeld(DeliveryBasis board, List<RoleSlipLedger.Held> held) {
        return board.plus(runs(held));
    }

    private static List<String> runs(List<RoleSlipLedger.Held> held) {
        return held.stream().map(RoleSlipLedger.Held::runId).toList();
    }

    private static List<String> messages(List<RoleSlipLedger.Held> held) {
        return held.stream().map(RoleSlipLedger.Held::message).toList();
    }

    private static String withHeldAdvice(String board, List<String> held) {
        if (held.isEmpty()) {
            return board;
        }
        StringBuilder text = new StringBuilder(board).append("\n\n").append(ABOUT_YOUR_ROSTER);
        held.forEach(advice -> text.append("\n\n").append(advice));
        return text.toString();
    }
}
