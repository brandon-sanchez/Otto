package otto.board;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.alerts.AlertDeliveryOutbox;
import otto.events.Event;
import otto.records.DecisionRecords;
import otto.records.Outcome;

/**
 * The full option list behind a waiver board's View all options button,
 * read from the board's Decision Record, so the list matches the board
 * the user read.
 */
@Component
public class WaiverBoardOptions {

    private final AlertDeliveryOutbox outbox;
    private final DecisionRecords records;
    private final String userId;

    public WaiverBoardOptions(AlertDeliveryOutbox outbox, DecisionRecords records,
            OttoProperties properties) {
        this.outbox = outbox;
        this.records = records;
        this.userId = properties.userId();
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
}
