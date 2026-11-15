package otto;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import otto.delivery.AlertDeliveryOutbox;
import otto.delivery.AlertIdSequence;
import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;
import otto.harness.OutboundStubs;
import otto.harness.WireSeamTest;
import otto.storage.JsonStore;
import otto.telegram.TelegramClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two Checks that overlap, as they can when the scheduled function has
 * no reserved concurrency: each container holds its own outbox over the
 * one shared store, and the Alert still goes out once.
 */
class OverlappingDeliveryScenarioTest extends WireSeamTest {

    private static final String KEY = "alert:edge:2026-w2:5850-over-8138";

    @Autowired
    private AlertDeliveryOutbox outbox;

    @Autowired
    private JsonStore store;

    @Autowired
    private AlertIdSequence ids;

    @Autowired
    private TelegramClient telegramClient;

    @Autowired
    private EventLog eventLog;

    @Autowired
    private Clock injectedClock;

    @Test
    void anAlertTwoOverlappingChecksBothDeliverGoesOutOnce() throws Exception {
        telegram.stubFor(post(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)).willReturn(
                aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"result\":{\"message_id\":1}}")
                        .withFixedDelay(1000)));
        AlertDeliveryOutbox otherContainer = new AlertDeliveryOutbox(store, ids, telegramClient,
                eventLog, injectedClock);
        Event alert = new Event(KEY, EventType.ALERT_SENT, clock.instant(), Map.of());

        CompletableFuture<List<Event>> first = CompletableFuture.supplyAsync(
                () -> outbox.deliver("Start Josh Jacobs.", "basis", List.of(alert)));
        awaitTelegramCalled();
        List<Event> during = otherContainer.deliver("Start Josh Jacobs.", "basis",
                List.of(alert));
        List<Event> delivered = first.get(10, TimeUnit.SECONDS);
        List<Event> after = otherContainer.deliver("Start Josh Jacobs.", "basis",
                List.of(alert));

        assertThat(delivered).extracting(Event::key).containsExactly(KEY);
        assertThat(during).isEmpty();
        assertThat(after).isEmpty();
        telegram.verify(1, postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)));
    }

    private static void awaitTelegramCalled() throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (telegram.findAll(postRequestedFor(urlEqualTo(OutboundStubs.SEND_MESSAGE_PATH)))
                .isEmpty()) {
            assertThat(System.nanoTime()).isLessThan(until);
            Thread.sleep(10);
        }
    }
}
