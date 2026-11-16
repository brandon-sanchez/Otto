package otto.board;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import otto.alerts.MuteStore;
import otto.settings.Trigger;

/**
 * Which waiver board reaches the user's chat this Check: the one
 * {@code otto.sent-waiver-board} names, unless the waiver trigger is
 * muted. The Check and the boards read it here, so they agree.
 */
@Component
public class BoardRouting {

    private final SentWaiverBoard sent;
    private final MuteStore muteStore;

    public BoardRouting(@Value("${otto.sent-waiver-board:EVIDENCE}") SentWaiverBoard sent,
            MuteStore muteStore) {
        this.sent = sent;
        this.muteStore = muteStore;
    }

    /** Returns the waiver board the property names as the one sent. */
    public SentWaiverBoard sent() {
        return sent;
    }

    /**
     * True when the evidence-based board goes to the user: it is the
     * board sent and the waiver trigger is not muted. Otherwise a round
     * it builds is recorded as a dry run.
     */
    public boolean evidenceBoardReachesUser() {
        return sent == SentWaiverBoard.EVIDENCE && !muteStore.muted(Trigger.WAIVER.muteTarget());
    }
}
