package otto.alerts;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;
import otto.sleeper.SourceResult;
import otto.telegram.TelegramClient;

/**
 * When a feed breaks, the user learns it from the assistant: one
 * self-report Alert per source, deduped through the Event Log.
 */
@Component
public class SelfReportService {

    private final EventLog eventLog;
    private final TelegramClient telegram;
    private final Clock clock;

    public SelfReportService(EventLog eventLog, TelegramClient telegram, Clock clock) {
        this.eventLog = eventLog;
        this.telegram = telegram;
        this.clock = clock;
    }

    /** Reports a source call that failed; an ok result is left alone. */
    public void reportIfUnavailable(SourceResult<?> result) {
        if (result instanceof SourceResult.Unavailable<?> unavailable) {
            report(unavailable.source(), unavailable.reason());
        }
    }

    public void report(String source, String reason) {
        String safeReason = Objects.requireNonNullElse(reason, "no detail");
        Event event = new Event(
                "source-unavailable:" + source,
                EventType.SOURCE_UNAVAILABLE,
                clock.instant(),
                Map.of("source", source, "reason", safeReason));
        if (eventLog.append(event)) {
            telegram.sendMessage(
                    "Heads up: I cannot read %s right now (%s). I keep watching everything else and will use it again once it recovers."
                            .formatted(source, safeReason));
        }
    }
}
