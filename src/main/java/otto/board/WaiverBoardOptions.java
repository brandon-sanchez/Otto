package otto.board;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.delivery.AlertDeliveryOutbox;
import otto.events.Event;
import otto.records.DecisionRecords;
import otto.records.Outcome;
import otto.storage.JsonStore;

/**
 * The full option list behind a waiver board's View all options button,
 * read from the board's Decision Record, so the list matches the board
 * the user read. A list Telegram cut short partway is resumed at the
 * first message that did not arrive, so the user never gets the start
 * of it twice.
 */
@Component
public class WaiverBoardOptions {

    /**
     * Prefix of the one document per board that holds where its list
     * resumes. {@code DocumentPlacement} names it a small record.
     */
    private static final String PROGRESS_PREFIX = "board-options-sent-";

    private final AlertDeliveryOutbox outbox;
    private final DecisionRecords records;
    private final JsonStore store;
    private final String userId;

    public WaiverBoardOptions(AlertDeliveryOutbox outbox, DecisionRecords records,
            JsonStore store, OttoProperties properties) {
        this.outbox = outbox;
        this.records = records;
        this.store = store;
        this.userId = properties.userId();
    }

    /**
     * How far an earlier send of one board's list got.
     *
     * @param resumeAt the index of the first message of the list that
     *        did not arrive; 0 when the last send finished or there was
     *        none
     */
    record Progress(int resumeAt) {
    }

    /**
     * Returns every entry ranked by the waiver board sent as this Alert,
     * as the Telegram messages to send in order.
     *
     * <p>Side effects: one Decision Record read.
     *
     * @return empty when no sent board with more options than it showed
     *         has that Alert id
     */
    public Optional<List<String>> allOptions(long alertId) {
        return outbox.sentEvents(alertId).stream()
                .map(Event::facts)
                .filter(facts -> facts.containsKey("runId") && facts.containsKey("leagueId"))
                .findFirst()
                .flatMap(facts -> records.read(userId, facts.get("leagueId"), facts.get("runId")))
                .map(record -> record.outcome() instanceof Outcome.SentBoard board
                        ? board.allOptions() : List.<String>of())
                .filter(all -> !all.isEmpty());
    }

    /**
     * Returns the messages of the board's list still to send: all of
     * them, or the rest after an earlier send that Telegram cut short.
     *
     * <p>Side effects: one Decision Record read and one document read.
     *
     * @return empty when {@link #allOptions} is empty
     */
    public Optional<List<String>> unsentOptions(long alertId) {
        int resumeAt = store.read(PROGRESS_PREFIX + alertId, Progress.class)
                .map(Progress::resumeAt)
                .orElse(0);
        return allOptions(alertId).map(all -> resumeAt < all.size()
                ? all.subList(resumeAt, all.size())
                : all);
    }

    /**
     * Records how many of the messages {@link #unsentOptions} returned
     * arrived, so the next tap resumes after them, or starts over once
     * they all did.
     *
     * <p>Side effects: one document read and one write.
     *
     * @param delivered how many arrived, from the start of that list
     * @param finished true when that was all of them
     */
    public void sent(long alertId, int delivered, boolean finished) {
        String name = PROGRESS_PREFIX + alertId;
        int resumeAt = store.read(name, Progress.class).map(Progress::resumeAt).orElse(0);
        store.write(name, new Progress(finished ? 0 : resumeAt + delivered));
    }
}
