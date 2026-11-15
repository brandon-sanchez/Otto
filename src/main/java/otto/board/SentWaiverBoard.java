package otto.board;

/**
 * Which waiver board reaches the user's chat, set by the
 * {@code otto.sent-waiver-board} property. Switching it is the rollback:
 * the board not sent is still built every round, so either can take
 * over at once.
 */
public enum SentWaiverBoard {

    /**
     * The evidence-based board {@link WaiverBoardService} writes and
     * checks; the scored board still runs silently as a baseline.
     */
    EVIDENCE,

    /**
     * The scored board the waiver Alert ranks by; the evidence-based
     * board is built and recorded as a dry run.
     */
    SCORED
}
