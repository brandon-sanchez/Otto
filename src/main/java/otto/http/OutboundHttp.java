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

    public static ClientHttpRequestFactory requestFactory(Duration readTimeout) {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
