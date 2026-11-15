package otto.harness;

import java.net.URI;
import java.util.Set;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

/**
 * The model the real-model suite asks for advice: every fact-checked
 * advice request the app sends to the stub LLM server is proxied to it
 * unchanged, model name and options included, so the suite judges the
 * model production uses. Phrasing requests stay on the canned stubs, so
 * a run pays only for the advice under test.
 *
 * <p>The API key never enters the Spring context: the proxy swaps it in
 * for the harness's test key on the way out.
 *
 * <p>Reads two environment variables:
 * <ul>
 *   <li>{@value #API_KEY} - the key sent upstream. Required.</li>
 *   <li>{@value #BASE_URL} - the upstream's base URL, without the
 *       {@code /chat/completions} path. Defaults to OpenAI's
 *       {@value #OPENAI_BASE_URL}.</li>
 * </ul>
 *
 * <p>To rehearse the suite without spending money, start a WireMock
 * server on this machine and point {@value #BASE_URL} at it. The
 * harness treats any loopback upstream as that rehearsal server: before
 * each test it clears the server's stubs and loads the test's canned
 * answer onto it, so every request still goes out through the proxy.
 * For example:
 *
 * <pre>
 * java -jar wiremock-standalone-3.13.1.jar --port 9911 &amp;
 * OPENAI_API_KEY=fake-upstream-key OTTO_REAL_MODEL_BASE_URL=http://localhost:9911 \
 *     mvn test -Preal-model
 * </pre>
 */
public final class RealModel {

    /** The JUnit tag that keeps the suite out of every default build. */
    public static final String TAG = "real-model";

    static final String API_KEY = "OPENAI_API_KEY";
    static final String BASE_URL = "OTTO_REAL_MODEL_BASE_URL";
    static final String OPENAI_BASE_URL = "https://api.openai.com/v1";

    private static final int PROXY_PRIORITY = OutboundStubs.ADVICE_PRIORITY - 1;

    private static final String ADVICE_REQUEST = "legalMoves";

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private RealModel() {
    }

    /**
     * Sends every advice request {@code llm} receives from here on to
     * the real model.
     *
     * @param rehearsalAnswer the answer a rehearsal server gives every
     *        advice request; it must pass the test's checks, so it is
     *        written for the test's league. Unused against a real model.
     * @throws IllegalStateException when {@value #API_KEY} is not set
     */
    public static void answersAdvice(WireMockServer llm, String rehearsalAnswer) {
        String key = System.getenv(API_KEY);
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("The real-model suite needs " + API_KEY
                    + " set to an OpenAI API key; see the Javadoc of RealModel.");
        }
        String baseUrl = System.getenv().getOrDefault(BASE_URL, OPENAI_BASE_URL);
        URI upstream = URI.create(baseUrl);
        if (LOOPBACK_HOSTS.contains(upstream.getHost())) {
            WireMock rehearsal = new WireMock(upstream.getScheme(), upstream.getHost(),
                    upstream.getPort());
            rehearsal.resetMappings();
            rehearsal.register(post(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                    .willReturn(okJson(OutboundStubs.contentBody(rehearsalAnswer))));
        }
        llm.stubFor(post(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .atPriority(PROXY_PRIORITY)
                .withRequestBody(containing(ADVICE_REQUEST))
                .willReturn(aResponse()
                        .proxiedFrom(baseUrl)
                        .withRemoveRequestHeader("Authorization")
                        .withAdditionalRequestHeader("Authorization", "Bearer " + key)));
    }
}
