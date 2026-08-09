package otto.http;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * Bounded timeouts for every outbound wire, so a hung remote host can
 * never stall a Check.
 */
public final class OutboundHttp {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private OutboundHttp() {
    }

    /**
     * The default wire: redirects are refused, so a source that starts
     * answering 3xx surfaces as a failure rather than following an
     * unexpected host.
     */
    public static ClientHttpRequestFactory requestFactory(Duration readTimeout) {
        return factory(readTimeout, HttpClient.Redirect.NEVER);
    }

    /**
     * A wire that follows redirects. GitHub answers every release-asset
     * URL with a 302 to its object store, so a client that refuses
     * redirects can never read one. Only download hosts need this.
     */
    public static ClientHttpRequestFactory followingRedirects(Duration readTimeout) {
        return factory(readTimeout, HttpClient.Redirect.NORMAL);
    }

    private static ClientHttpRequestFactory factory(Duration readTimeout,
            HttpClient.Redirect redirects) {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(redirects)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
