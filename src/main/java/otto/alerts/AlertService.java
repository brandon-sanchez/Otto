package otto.alerts;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;
import otto.telegram.TelegramClient;

/**
 * Turns Snapshot Diff events into Alerts: detect, gate, phrase, send,
 * and record. An Alert is recorded in the Event Log only when Telegram
 * accepted it, so a failed send retries on the next Check. The Event
 * Log's exact-duplicate dedup of diff events plus the recorded Alert
 * key make sure the same decline never Alerts twice.
 */
@Component
public class AlertService {

    /**
     * A diff event older than this never Alerts. It bounds send
     * retries and keeps stale declines silent after state changes.
     */
    private static final Duration RETRY_WINDOW = Duration.ofHours(1);

    private final StatusDeclineDetector detector;
    private final AlertPhraser phraser;
    private final TelegramClient telegram;
    private final EventLog eventLog;
    private final Clock clock;

    public AlertService(StatusDeclineDetector detector, AlertPhraser phraser,
            TelegramClient telegram, EventLog eventLog, Clock clock) {
        this.detector = detector;
        this.phraser = phraser;
        this.telegram = telegram;
        this.eventLog = eventLog;
        this.clock = clock;
    }

    /**
     * Detects, gates, phrases, and sends Alerts for the pending
     * Snapshot Diff events inside the retry window.
     *
     * @return the alert events recorded for sent Alerts
     */
    public List<Event> processPending() {
        Instant now = clock.instant();
        List<Event> pending = eventLog.all().stream()
                .filter(event -> event.type() == EventType.SNAPSHOT_DIFF)
                .filter(event -> Duration.between(event.at(), now).compareTo(RETRY_WINDOW) <= 0)
                .filter(event -> !eventLog.contains("alert:" + event.key()))
                .toList();

        List<Event> alerts = new ArrayList<>();
        for (Event event : pending) {
            detector.detect(event).ifPresent(recommendation -> {
                if (recommendation.confidence() == Confidence.LOW) {
                    return;
                }
                String text = phraser.phrase(event, recommendation);
                if (!telegram.sendMessage(text)) {
                    return;
                }
                Event alert = new Event(
                        "alert:" + event.key(),
                        EventType.ALERT_SENT,
                        clock.instant(),
                        Map.of(
                                "playerId", recommendation.playerId(),
                                "player", recommendation.player(),
                                "action", recommendation.action(),
                                "confidence", recommendation.confidence().name(),
                                "pros", String.join("; ", recommendation.pros()),
                                "cons", String.join("; ", recommendation.cons()),
                                "text", text));
                if (eventLog.append(alert)) {
                    alerts.add(alert);
                }
            });
        }
        return alerts;
    }
}
