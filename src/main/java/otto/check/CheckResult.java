package otto.check;

import java.util.List;

import otto.directory.PlayerDirectoryService;
import otto.events.Event;

/**
 * The outcome of one Check. A skipped result means the cadence gate
 * ended the Check before any polling; all other fields are then empty.
 *
 * @param newEvents what this Check added to the Event Log before Alert
 *        detection ran: the Snapshot Diff, and the Watchlist moves that
 *        no Snapshot carries
 */
public record CheckResult(
        boolean skipped,
        PlayerDirectoryService.Update directory,
        List<Event> newEvents,
        List<Event> alertsSent) {

    public static CheckResult skippedByCadence() {
        return new CheckResult(true,
                new PlayerDirectoryService.Update.Skipped(), List.of(), List.of());
    }
}
