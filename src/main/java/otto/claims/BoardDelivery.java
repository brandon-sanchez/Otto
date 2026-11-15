package otto.claims;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import otto.alerts.AlertDeliveryOutbox;
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

    /** True once Telegram has accepted a board for this round. */
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
     * @param facts the facts the Alert Event records; the sent text and
     *        the held runs are added here
     * @return the Event recorded when Telegram accepted the board
     */
    public Optional<Event> deliver(ClaimRound round, String basis,
            Function<List<String>, List<String>> withHeldAdvice, boolean viewAllOptions,
            Map<String, String> facts) {
        List<RoleSlipLedger.Held> held = roleSlips.heldForBoard();
        List<String> parts = withHeldAdvice.apply(messages(held));
        return send(round, basis(basis, held), parts, viewAllOptions, facts, held);
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
    public Optional<Event> deliverPhrased(ClaimRound round, String basis,
            Supplier<String> phrase, Map<String, String> facts) {
        List<RoleSlipLedger.Held> held = roleSlips.heldForBoard();
        String fullBasis = basis(basis, held);
        String text = outbox.pendingText(List.of(round.alertKey()), fullBasis)
                .orElseGet(() -> withHeldAdvice(phrase.get(), messages(held)));
        return send(round, fullBasis, List.of(text), false, facts, held);
    }

    private Optional<Event> send(ClaimRound round, String basis, List<String> parts,
            boolean viewAllOptions, Map<String, String> facts, List<RoleSlipLedger.Held> held) {
        List<String> heldRuns = held.stream().map(RoleSlipLedger.Held::runId).toList();
        Map<String, String> recorded = new HashMap<>(facts);
        recorded.put("text", String.join("\n\n", parts));
        if (!heldRuns.isEmpty()) {
            recorded.put("heldAdviceRuns", String.join(",", heldRuns));
        }
        Optional<Event> delivered = outbox.deliver(parts, viewAllOptions, basis,
                        List.of(new Event(round.alertKey(), EventType.ALERT_SENT, round.now(),
                                Map.copyOf(recorded))))
                .stream().findFirst();
        if (delivered.isPresent() && !heldRuns.isEmpty()) {
            roleSlips.deliveredInBoard(heldRuns);
        }
        return delivered;
    }

    /**
     * What a board's messages were written from: its own basis and the
     * runs of the held advice it carries, so the same board with
     * different advice under it counts as a new delivery.
     */
    private static String basis(String board, List<RoleSlipLedger.Held> held) {
        return held.isEmpty() ? board : board + "+" + String.join("+",
                held.stream().map(RoleSlipLedger.Held::runId).toList());
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
