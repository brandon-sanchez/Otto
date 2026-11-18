package otto.nflverse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import otto.storage.DocumentBackend;
import otto.storage.JsonStore;

import static org.assertj.core.api.Assertions.assertThat;

class StoredWithoutDropbacksTest {

    private static final Instant AT = Instant.parse("2026-10-01T12:00:00Z");

    private final Map<String, byte[]> documents = new HashMap<>();

    private final JsonStore store = new JsonStore(new DocumentBackend() {
        @Override
        public Optional<byte[]> load(String name) {
            return Optional.ofNullable(documents.get(name));
        }

        @Override
        public void store(String name, byte[] json) {
            documents.put(name, json);
        }

        @Override
        public void delete(String name) {
            documents.remove(name);
        }
    });

    /** Stores the value, then drops its dropbacks field, as a copy from before it held them. */
    private void storeWithoutDropbacks(String name, Object value) {
        store.write(name, value);
        store.flush();
        String json = new String(documents.get(name), StandardCharsets.UTF_8);
        String without = json.replaceFirst(",\\s*\"dropbacks\"\\s*:\\s*\\[\\s*]", "");
        assertThat(without).doesNotContain("dropbacks");
        documents.put(name, without.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aChartingCopyWithoutDropbacksLoadsHoldingNone() {
        storeWithoutDropbacks("ftn", new FtnCharting("2026", AT, AT,
                new Coverage(1, List.of()), List.of(), List.of()));

        assertThat(store.read("ftn", FtnCharting.class))
                .hasValueSatisfying(charting -> assertThat(charting.dropbacks()).isEmpty());
    }

    @Test
    void aPlayByPlayCopyWithoutDropbacksLoadsHoldingNone() {
        storeWithoutDropbacks("pbp", new PlayByPlay("2026", false, AT, AT,
                new Coverage(1, List.of()), List.of(), List.of()));

        assertThat(store.read("pbp", PlayByPlay.class))
                .hasValueSatisfying(plays -> assertThat(plays.dropbacks()).isEmpty());
    }
}
