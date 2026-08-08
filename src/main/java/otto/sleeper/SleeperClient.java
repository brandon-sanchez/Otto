package otto.sleeper;

import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import otto.OttoProperties;
import otto.http.OutboundHttp;
import otto.storage.OttoJson;

/**
 * The wire to Sleeper. Every call is a conditional GET; every failure
 * becomes a typed {@link SourceResult.Unavailable}. Schema validation
 * of the returned JSON happens in {@link SleeperAdapter}.
 */
@Component
public class SleeperClient {

    /** Generous read timeout: the players file is a multi-megabyte download. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final RestClient http;

    public SleeperClient(OttoProperties properties) {
        this.http = RestClient.builder()
                .baseUrl(properties.sleeper().baseUrl())
                .requestFactory(OutboundHttp.requestFactory(READ_TIMEOUT))
                .build();
    }

    /**
     * Fetches the path with a conditional GET.
     *
     * @param etag the ETag from the last stored copy, or null on first fetch
     */
    public SourceResult<Fetched> get(String path, String etag) {
        try {
            RestClient.RequestHeadersSpec<?> request = http.get().uri(path);
            if (etag != null) {
                request = request.header(HttpHeaders.IF_NONE_MATCH, etag);
            }
            ResponseEntity<byte[]> response = request.retrieve().toEntity(byte[].class);
            if (response.getStatusCode() == HttpStatus.NOT_MODIFIED) {
                return new SourceResult.Ok<>(Fetched.notModified(etag));
            }
            JsonNode body = OttoJson.MAPPER.readTree(response.getBody());
            return new SourceResult.Ok<>(Fetched.of(body, response.getHeaders().getETag()));
        } catch (Exception e) {
            return new SourceResult.Unavailable<>("sleeper:" + path, e.getMessage());
        }
    }

    /** One fetched response. The body is null when the answer was 304 Not Modified. */
    public record Fetched(JsonNode body, String etag, boolean notModified) {

        static Fetched of(JsonNode body, String etag) {
            return new Fetched(body, etag, false);
        }

        static Fetched notModified(String etag) {
            return new Fetched(null, etag, true);
        }
    }
}
