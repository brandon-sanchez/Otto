package otto.check;

import java.util.List;

import otto.directory.PlayerDirectoryService;
import otto.events.Event;

/**
 * The outcome of one Check. A skipped result means the cadence gate
 * ended the Check before any polling; all other fields are then empty.
 */
public record CheckResult(
        boolean skipped,
        PlayerDirectoryService.Update directory,
        List<Event> newDiffEvents,
        List<Event> alertsSent) {

    public static CheckResult skippedByCadence() {
        return new CheckResult(true,
                new PlayerDirectoryService.Update.Skipped(), List.of(), List.of());
    }
}
