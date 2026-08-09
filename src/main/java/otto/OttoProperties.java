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
        Duration directoryCheckInterval,
        Duration preDraftCheckInterval,
        double edgeThreshold) {

    public record Sleeper(String baseUrl) {
    }

    public record Telegram(String baseUrl, String botToken, String chatId,
            String webhookSecret) {
    }
}
