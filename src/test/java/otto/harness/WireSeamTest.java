package otto.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import otto.format.TelegramHtml;
import otto.storage.OttoJson;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one seam all tests use: boot the real Spring context, invoke entry
 * points directly. Stub HTTP servers play Sleeper, Telegram, nflverse, the
 * team sites, FantasyCalc, and the LLM.
 * Storage goes to a wiped-per-test temp directory. The clock is injected.
 */
@SpringBootTest
@Import(WireSeamTest.HarnessConfig.class)
public abstract class WireSeamTest {

    public static final Instant TEST_START = Instant.parse("2026-09-15T17:00:00Z");

    /** The secret token Telegram must echo back on every webhook call. */
    public static final String WEBHOOK_SECRET = "test-webhook-secret";

    /** The one chat the assistant serves. */
    public static final long USER_CHAT_ID = 4242;

    protected static final WireMockServer sleeper = newServer();
    protected static final WireMockServer telegram = newServer();
    protected static final WireMockServer llm = newServer();
    protected static final WireMockServer nflverse = newServer();
    protected static final WireMockServer teamSites = newServer();
    protected static final WireMockServer fantasyCalc = newServer();
    private static final Path storageDir = newStorageDir();

    /** Every message body sent to Telegram during one test, before any reset. */
    private static final List<String> sentBodies = new CopyOnWriteArrayList<>();

    static {
        telegram.addMockServiceRequestListener((request, response) -> {
            if (request.getUrl().endsWith("/sendMessage")) {
                sentBodies.add(request.getBodyAsString());
            }
        });
    }

    private static WireMockServer newServer() {
        WireMockServer server = new WireMockServer(
                WireMockConfiguration.options().dynamicPort().gzipDisabled(true));
        server.start();
        return server;
    }

    private static Path newStorageDir() {
        try {
            return Files.createTempDirectory("otto-wire-seam");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void wireProperties(DynamicPropertyRegistry registry) {
        registry.add("otto.sleeper.base-url", sleeper::baseUrl);
        registry.add("otto.user-id", () -> "777001");
        registry.add("otto.telegram.base-url", telegram::baseUrl);
        registry.add("otto.telegram.bot-token", () -> "test-bot-token");
        registry.add("otto.telegram.chat-id", () -> String.valueOf(USER_CHAT_ID));
        registry.add("otto.telegram.webhook-secret", () -> WEBHOOK_SECRET);
        registry.add("otto.storage-dir", () -> storageDir.toString());
        // One stub server plays all three nflverse-side hosts: the GitHub
        // release API, the release downloads, and the DynastyProcess file.
        registry.add("otto.nflverse.api-base-url", nflverse::baseUrl);
        registry.add("otto.nflverse.download-base-url", nflverse::baseUrl);
        registry.add("otto.nflverse.player-ids-base-url", nflverse::baseUrl);
        registry.add("otto.market.fantasy-calc-base-url", fantasyCalc::baseUrl);
        registry.add("otto.market.dynasty-process-base-url", nflverse::baseUrl);
        registry.add("otto.team-sites.url-template", () -> teamSites.baseUrl() + "/{host}");
        registry.add("spring.ai.openai.base-url", llm::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "test-llm-key");
    }

    @Autowired
    protected MutableClock clock;

    @Autowired
    private otto.sleeper.SleeperCache sleeperCache;

    @Autowired
    private otto.model.ProviderBreakers breakers;

    /**
     * Holds every scenario to what the user sees in Telegram: no literal
     * Markdown marks, no tag shown as text, and HTML that Telegram parses,
     * with only supported tags, each closed inside its own message.
     */
    @AfterEach
    void everySentMessageIsCleanTelegramHtml() throws IOException {
        try {
            for (String body : sentBodies) {
                JsonNode message = OttoJson.MAPPER.readTree(body);
                String text = message.path("text").asText();
                assertThat(text).as("a sent message")
                        .doesNotContain("**");
                if (message.path("parse_mode").asText().equals("HTML")) {
                    assertThat(TelegramHtml.normalize(text))
                            .as("a sent message as Telegram HTML").isEqualTo(text);
                    assertThat(TelegramHtml.visible(text)).as("a sent message as the user sees it")
                            .doesNotContainPattern("</?[a-zA-Z]+[^<>]*>");
                }
            }
        } finally {
            sentBodies.clear();
        }
    }

    @BeforeEach
    void resetSeam() throws IOException {
        sentBodies.clear();
        sleeper.resetAll();
        telegram.resetAll();
        llm.resetAll();
        nflverse.resetAll();
        teamSites.resetAll();
        fantasyCalc.resetAll();
        clock.set(TEST_START);
        sleeperCache.clear();
        breakers.reset();
        wipe(storageDir);
    }

    private static void wipe(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(dir))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
    }

    @TestConfiguration
    public static class HarnessConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(TEST_START, ZoneId.of("America/Los_Angeles"));
        }
    }
}
