package otto.nflverse;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import otto.OttoProperties;
import otto.http.OutboundHttp;
import otto.sleeper.SourceResult;
import otto.storage.OttoJson;

/**
 * The wire to the nflverse data releases and to the DynastyProcess
 * player-id mapping. Every failure becomes a typed
 * {@link SourceResult.Unavailable}; nothing here throws into a job.
 *
 * The releases are GitHub release assets. Their index carries an
 * updated-at timestamp per asset, which is how a refresh decides
 * whether a download is worth its bytes: the season files run to tens
 * of megabytes and change at most once a day.
 */
@Component
public class NflverseClient {

    /** The nflverse-data repository, whose releases hold every season file. */
    private static final String RELEASES = "/repos/nflverse/nflverse-data/releases/tags/";
    private static final String DOWNLOADS = "/nflverse/nflverse-data/releases/download/";
    private static final String PLAYER_IDS = "/dynastyprocess/data/master/files/db_playerids.csv";

    /** Generous: these downloads are multi-megabyte CSV bodies. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private final RestClient api;
    private final RestClient downloads;
    private final RestClient playerIds;

    public NflverseClient(OttoProperties properties) {
        OttoProperties.Nflverse nflverse = properties.nflverse();
        this.api = client(nflverse.apiBaseUrl());
        this.downloads = client(nflverse.downloadBaseUrl());
        this.playerIds = client(nflverse.playerIdsBaseUrl());
    }

    /**
     * Every host here redirects: a GitHub release-asset URL always
     * answers 302 to the object store that holds the bytes, and the
     * API moves resources the same way. A client that refused
     * redirects could never read a single file.
     */
    private static RestClient client(String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(OutboundHttp.followingRedirects(READ_TIMEOUT))
                .build();
    }

    /**
     * The publish time of every asset in one release, by file name.
     * This is the timestamp check: an unchanged value means the stored
     * copy is still current and no body needs downloading.
     */
    SourceResult<Map<String, Instant>> assetTimestamps(String releaseTag) {
        String path = RELEASES + releaseTag;
        try {
            byte[] body = api.get().uri(path).retrieve().body(byte[].class);
            JsonNode release = OttoJson.MAPPER.readTree(body);
            JsonNode assets = release.path("assets");
            if (!assets.isArray() || assets.isEmpty()) {
                return drift(releaseTag, "release carries no assets");
            }
            Map<String, Instant> timestamps = new HashMap<>();
            for (JsonNode asset : assets) {
                if (asset.hasNonNull("name") && asset.hasNonNull("updated_at")) {
                    timestamps.put(asset.get("name").asText(),
                            Instant.parse(asset.get("updated_at").asText()));
                }
            }
            if (timestamps.isEmpty()) {
                return drift(releaseTag, "no asset carries a name and an updated_at");
            }
            return new SourceResult.Ok<>(timestamps);
        } catch (Exception e) {
            return unavailable(releaseTag, e);
        }
    }

    /**
     * Streams one release asset through the given reader. The body is
     * never held whole: the reader sees rows as they arrive and keeps
     * only what it needs.
     */
    <T> SourceResult<T> downloadAsset(String releaseTag, String asset,
            Function<Stream<Csv.Row>, T> rows) {
        return download(downloads, DOWNLOADS + releaseTag + "/" + asset, releaseTag + "/" + asset,
                rows);
    }

    /**
     * The DynastyProcess mapping that joins Sleeper players to nflverse
     * rows. It is published as a plain file with no release index, so
     * "download only on change" is an ETag conditional GET here rather
     * than a timestamp comparison.
     *
     * @param etag the ETag of the stored copy, or null on first fetch
     */
    <T> SourceResult<Downloaded<T>> downloadPlayerIds(String etag,
            Function<Stream<Csv.Row>, T> rows) {
        try {
            RestClient.RequestHeadersSpec<?> request = playerIds.get().uri(PLAYER_IDS);
            if (etag != null) {
                request = request.header(HttpHeaders.IF_NONE_MATCH, etag);
            }
            return new SourceResult.Ok<>(request.exchange((outbound, response) -> {
                if (response.getStatusCode() == HttpStatus.NOT_MODIFIED) {
                    return Downloaded.notModified(etag);
                }
                return new Downloaded<>(readRows(response, rows),
                        response.getHeaders().getETag(), false);
            }));
        } catch (Exception e) {
            return unavailable("player-ids", e);
        }
    }

    /** One conditional download: the rows and their ETag, or nothing new. */
    record Downloaded<T>(T value, String etag, boolean notModified) {

        static <T> Downloaded<T> notModified(String etag) {
            return new Downloaded<>(null, etag, true);
        }
    }

    private <T> SourceResult<T> download(RestClient http, String path, String source,
            Function<Stream<Csv.Row>, T> rows) {
        try {
            return new SourceResult.Ok<>(http.get().uri(path)
                    .exchange((request, response) -> readRows(response, rows)));
        } catch (Exception e) {
            return unavailable(source, e);
        }
    }

    private static <T> T readRows(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response,
            Function<Stream<Csv.Row>, T> rows) throws IOException {
        HttpStatusCode status = response.getStatusCode();
        if (!status.is2xxSuccessful()) {
            throw new IllegalStateException("HTTP " + status.value());
        }
        return rows.apply(Csv.stream(response.getBody()));
    }

    private static <T> SourceResult<T> unavailable(String source, Exception cause) {
        String reason = cause.getMessage();
        return new SourceResult.Unavailable<>("nflverse:" + source,
                reason == null ? cause.getClass().getSimpleName() : reason);
    }

    private static <T> SourceResult<T> drift(String source, String reason) {
        return new SourceResult.Unavailable<>("nflverse:" + source, "schema drift: " + reason);
    }
}
