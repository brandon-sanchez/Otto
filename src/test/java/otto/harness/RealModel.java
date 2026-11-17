package otto.harness;

import java.net.URI;
import java.util.Set;
import java.util.function.Consumer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

/**
 * The model the real-model suite asks: every request the app sends to
 * the stub LLM server that the suite judges is proxied to it unchanged,
 * model name and options included, so the suite judges the model
 * production uses. Alert phrasing stays on the canned stubs, so a run
 * pays only for the answers under test.
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
 * each question it clears the server's stubs and loads the question's
 * canned answers onto it, so every request still goes out through the
 * proxy. The server keeps every request it was sent, so its request log
 * after a run shows what a real run would send and roughly what it
 * would cost. For example:
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

    private static final String TOOLS_FIELD = "\"tools\"";

    private static final String TOOL_ROLE_MESSAGE = "\"role\":\"tool\"";

    private static final String REHEARSAL_NARRATION = "Here is what I found.";

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
        Upstream upstream = Upstream.fromEnvironment();
        upstream.rehearse(rehearsal -> rehearsal.register(advice(rehearsalAnswer)));
        upstream.proxy(llm, ADVICE_REQUEST);
    }

    /**
     * Sends the user's next chat question to the real model end to end:
     * the Ask loop that picks a tool and its arguments, and the
     * fact-checked answer the tool asks for.
     *
     * @param rehearsal what a rehearsal server answers in the real
     *        model's place. Unused against a real model.
     * @throws IllegalStateException when {@value #API_KEY} is not set
     */
    public static void answersChat(WireMockServer llm, Rehearsal rehearsal) {
        Upstream upstream = Upstream.fromEnvironment();
        upstream.rehearse(server -> {
            if (rehearsal.advice() != null) {
                server.register(advice(rehearsal.advice()));
            }
            server.register(post(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                    .atPriority(OutboundStubs.ADVICE_PRIORITY + 1)
                    .withRequestBody(containing(TOOL_ROLE_MESSAGE))
                    .willReturn(okJson(OutboundStubs.contentBody(REHEARSAL_NARRATION))));
            server.register(post(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                    .atPriority(OutboundStubs.ADVICE_PRIORITY + 2)
                    .withRequestBody(containing(TOOLS_FIELD))
                    .willReturn(okJson(OutboundStubs.toolCallBody(rehearsal.tool(),
                            rehearsal.arguments()))));
        });
        upstream.proxy(llm, ADVICE_REQUEST);
        upstream.proxy(llm, TOOLS_FIELD);
    }

    private static MappingBuilder advice(String answer) {
        return post(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                .atPriority(OutboundStubs.ADVICE_PRIORITY)
                .withRequestBody(containing(ADVICE_REQUEST))
                .willReturn(okJson(OutboundStubs.contentBody(answer)));
    }

    /**
     * What a rehearsal server answers for one chat question: the tool
     * call the Ask loop makes, and the checked answer that tool's advice
     * request gets.
     *
     * @param tool the tool the Ask loop calls, such as {@code suggest_trades}
     * @param arguments the tool's arguments as JSON
     * @param advice the answer to the tool's advice request, or
     *        {@code null} for a tool whose answer code writes alone
     */
    public record Rehearsal(String tool, String arguments, String advice) {

        /** A tool whose answer code writes alone, with no advice request. */
        public static Rehearsal codeWritten(String tool, String arguments) {
            return new Rehearsal(tool, arguments, null);
        }
    }

    private record Upstream(String baseUrl, String key) {

        static Upstream fromEnvironment() {
            String key = System.getenv(API_KEY);
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("The real-model suite needs " + API_KEY
                        + " set to an OpenAI API key; see the Javadoc of RealModel.");
            }
            return new Upstream(System.getenv().getOrDefault(BASE_URL, OPENAI_BASE_URL), key);
        }

        void rehearse(Consumer<WireMock> answers) {
            URI upstream = URI.create(baseUrl);
            if (!LOOPBACK_HOSTS.contains(upstream.getHost())) {
                return;
            }
            WireMock rehearsal = new WireMock(upstream.getScheme(), upstream.getHost(),
                    upstream.getPort());
            rehearsal.removeMappings();
            answers.accept(rehearsal);
        }

        void proxy(WireMockServer llm, String requestContains) {
            llm.stubFor(post(urlPathMatching(OutboundStubs.CHAT_COMPLETIONS_PATH))
                    .atPriority(PROXY_PRIORITY)
                    .withRequestBody(containing(requestContains))
                    .willReturn(aResponse()
                            .proxiedFrom(baseUrl)
                            .withRemoveRequestHeader("Authorization")
                            .withAdditionalRequestHeader("Authorization", "Bearer " + key)));
        }
    }
}
