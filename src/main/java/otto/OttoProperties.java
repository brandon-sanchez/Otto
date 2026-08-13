package otto;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import otto.lineup.PositionCutoffs;

/**
 * @param notableCutoffs the ranks that make a dropped player league
 *        news, part of the Settings document the spec names
 * @param replacementCutoffs the ranks that price replacement level for
 *        a starting slot
 */
@ConfigurationProperties(prefix = "otto")
public record OttoProperties(
        String leagueId,
        String username,
        String storageDir,
        Sleeper sleeper,
        Telegram telegram,
        Nflverse nflverse,
        Aws aws,
        Duration directoryCheckInterval,
        Duration preDraftCheckInterval,
        Duration sleeperCadence,
        double edgeThreshold,
        PositionCutoffs notableCutoffs,
        PositionCutoffs replacementCutoffs) {

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

    /**
     * Where the deployed assistant keeps its documents. The stack names
     * the bucket and the table and hands both to the function as
     * environment variables, so nothing here is checked in. Every field
     * is null on a laptop, where the local file storage is used instead.
     */
    public record Aws(String bucket, String table) {
    }
}
