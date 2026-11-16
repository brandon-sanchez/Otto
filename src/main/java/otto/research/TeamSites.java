package otto.research;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import otto.http.OutboundHttp;

/**
 * The wire to the 32 team sites. It reads only a path on one of the
 * hosts in {@link OfficialTeams}, so an address taken from a feed can
 * never send a request anywhere else, and every failure comes back as a
 * {@link Response.Failed} rather than an exception.
 */
@Component
class TeamSites {

    static final String USER_AGENT = "Otto/1.0 (personal fantasy football assistant)";

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private static final Pattern SAFE_PATH = Pattern.compile("(/[A-Za-z0-9._~-]+)+/?");

    private final RestClient http;
    private final String urlTemplate;

    /**
     * @param urlTemplate the site address with {@code {host}} where the
     *        team's host goes
     */
    TeamSites(@Value("${otto.team-sites.url-template}") String urlTemplate) {
        this.urlTemplate = urlTemplate;
        this.http = RestClient.builder()
                .requestFactory(OutboundHttp.requestFactory(READ_TIMEOUT))
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .build();
    }

    /**
     * What a server last said about its copy, sent back so an unchanged
     * resource can answer 304.
     *
     * @param etag the {@code ETag} header, null when none was sent
     * @param lastModified the {@code Last-Modified} header, null when none was sent
     */
    record Validators(String etag, String lastModified) {

        static final Validators NONE = new Validators(null, null);
    }

    /** One answer from a team site. */
    sealed interface Response {

        /** The resource, with the validators to send next time. */
        record Body(String text, Validators validators) implements Response {
        }

        /** The resource has not changed since the validators sent. */
        record NotModified() implements Response {
        }

        /** No usable answer, and why. */
        record Failed(String reason) implements Response {
        }
    }

    /**
     * Reads one path on a team's site with a conditional GET.
     *
     * <p>Side effect: one HTTP request, unless the path is refused.
     *
     * @param path an absolute path such as {@code /rss/news}; one with a
     *        query, a parent step or an unusual character is refused
     *        without a request
     */
    Response get(OfficialTeams.Team team, String path, Validators validators) {
        if (!SAFE_PATH.matcher(path).matches() || path.contains("..")) {
            return new Response.Failed("refused path " + path);
        }
        URI uri = URI.create(urlTemplate.replace("{host}", team.host()) + path);
        try {
            return http.get().uri(uri)
                    .headers(headers -> {
                        if (validators.etag() != null) {
                            headers.set(HttpHeaders.IF_NONE_MATCH, validators.etag());
                        }
                        if (validators.lastModified() != null) {
                            headers.set(HttpHeaders.IF_MODIFIED_SINCE, validators.lastModified());
                        }
                    })
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isSameCodeAs(HttpStatus.NOT_MODIFIED)) {
                            return new Response.NotModified();
                        }
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            return new Response.Failed("HTTP " + response.getStatusCode().value());
                        }
                        byte[] body = response.getBody().readNBytes(MAX_BODY_BYTES + 1);
                        if (body.length > MAX_BODY_BYTES) {
                            return new Response.Failed("body larger than " + MAX_BODY_BYTES
                                    + " bytes");
                        }
                        return new Response.Body(new String(body, StandardCharsets.UTF_8),
                                new Validators(response.getHeaders().getETag(),
                                        response.getHeaders().getFirst(
                                                HttpHeaders.LAST_MODIFIED)));
                    });
        } catch (RestClientException e) {
            return new Response.Failed(failure(e));
        }
    }

    private static String failure(RestClientException e) {
        Throwable cause = e.getCause() instanceof IOException io ? io : e;
        return cause.getClass().getSimpleName()
                + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }
}
