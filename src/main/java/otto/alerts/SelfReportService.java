package otto.alerts;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;
import otto.scope.LeagueScope;
import otto.sleeper.SourceResult;
import otto.telegram.TelegramClient;

/**
 * When a feed breaks, the user learns it from the assistant: one
 * self-report Alert per source, deduped through the Event Log.
 *
 * <p>A league's own Sleeper data is reported for that league. Every
 * other source, such as nflverse, Sleeper's NFL state and projections or
 * the Player Directory, is shared by every followed league, so its
 * outage is reported once, naming no league, and deduped in the config
 * league's Event Log.
 */
@Component
public class SelfReportService {

    /** The source names of one league's own Sleeper data, which name the league's path. */
    private static final String LEAGUE_SOURCE_PREFIX = "sleeper:/v1/league/";

    /** The source name of a league document missing its roster or scoring settings. */
    private static final String LEAGUE_SETTINGS_SOURCE = "sleeper:league-settings";

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
        if (belongsToOneLeague(source)) {
            send(source, reason);
        } else {
            LeagueScope.runForNoLeague(() -> send(source, reason));
        }
    }

    private void send(String source, String reason) {
        String safeReason = Objects.requireNonNullElse(reason, "no detail");
        Event event = new Event(
                "source-unavailable:" + source,
                EventType.SOURCE_UNAVAILABLE,
                clock.instant(),
                Map.of("source", source, "reason", safeReason));
        if (eventLog.append(event)) {
            telegram.sendMessage(
                    ("Heads up, I can't read %s right now, and the error says %s. I'm still "
                            + "watching everything else, and I'll use it again once it's back.")
                            .formatted(source, safeReason));
        }
    }

    private static boolean belongsToOneLeague(String source) {
        return source.startsWith(LEAGUE_SOURCE_PREFIX) || source.equals(LEAGUE_SETTINGS_SOURCE);
    }
}
