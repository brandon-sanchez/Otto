package otto.harness;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.stream.Stream;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The one seam all tests use: boot the real Spring context, invoke entry
 * points directly. Stub HTTP servers play Sleeper, Telegram, and the LLM.
 * Storage goes to a wiped-per-test temp directory. The clock is injected.
 */
@SpringBootTest
@Import(WireSeamTest.HarnessConfig.class)
public abstract class WireSeamTest {

    public static final Instant TEST_START = Instant.parse("2026-09-15T17:00:00Z");

    protected static final WireMockServer sleeper = newServer();
    protected static final WireMockServer telegram = newServer();
    protected static final WireMockServer llm = newServer();
    private static final Path storageDir = newStorageDir();

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
        registry.add("otto.telegram.base-url", telegram::baseUrl);
        registry.add("otto.telegram.bot-token", () -> "test-bot-token");
        registry.add("otto.telegram.chat-id", () -> "4242");
        registry.add("otto.storage-dir", () -> storageDir.toString());
        registry.add("spring.ai.openai.base-url", llm::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "test-llm-key");
    }

    @Autowired
    protected MutableClock clock;

    @Autowired
    private otto.sleeper.SleeperCache sleeperCache;

    @BeforeEach
    void resetSeam() throws IOException {
        sleeper.resetAll();
        telegram.resetAll();
        llm.resetAll();
        clock.set(TEST_START);
        sleeperCache.clear();
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
