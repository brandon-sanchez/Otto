package otto;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "otto")
public record OttoProperties(
        String leagueId,
        String username,
        String storageDir,
        Sleeper sleeper,
        Telegram telegram,
        Nflverse nflverse,
        Duration directoryCheckInterval,
        Duration preDraftCheckInterval,
        Duration sleeperCadence,
        double edgeThreshold) {

    public record Sleeper(String baseUrl) {
    }

    public record Telegram(String baseUrl, String botToken, String chatId,
            String webhookSecret) {
    }

    /**
     * The nflverse side of the data plan. The release index and the
     * release assets live on different hosts, and the DynastyProcess
     * player-id mapping on a third, so each carries its own base URL.
     */
    public record Nflverse(String apiBaseUrl, String downloadBaseUrl, String playerIdsBaseUrl,
            Duration checkInterval) {
    }
}
